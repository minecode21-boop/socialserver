import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.*;

public class SocialServer {

    static final int PORT = 9999;
    static final String DB_URL = "jdbc:sqlite:social_new.db";
    static final long ONLINE_TIMEOUT = 30000;
    
    // TYPING & PRESENCE
    static Map<String, Long> typingMap = new ConcurrentHashMap<>();
    static Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    
    // WEBRTC SIGNALING MAILBOX: Key = Receiver, Value = List of Signals (JSON Strings)
    static Map<String, List<String>> signalMailbox = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println(">> STARTING SERVER ON PORT " + PORT + "...");
        initDB();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        server.createContext("/", new StaticHandler());
        server.createContext("/api/register", new AuthHandler("register"));
        server.createContext("/api/login", new AuthHandler("login"));
        server.createContext("/api/request", new FriendHandler("request"));
        server.createContext("/api/accept", new FriendHandler("accept"));
        server.createContext("/api/getfriends", new FriendHandler("list"));
        server.createContext("/api/send", new ChatHandler("send"));
        server.createContext("/api/getchat", new ChatHandler("get"));
        server.createContext("/api/avatar", new AvatarHandler());
        server.createContext("/api/search", new SearchHandler());
        server.createContext("/api/typing", new TypingHandler());
        
        // NEW: SIGNALING FOR CALLS
        server.createContext("/api/signal", new SignalHandler());

        server.setExecutor(null);
        System.out.println(">> SERVER RUNNING! GO TO: http://localhost:" + PORT);
        server.start();
    }

    public static Connection connect() throws SQLException { return DriverManager.getConnection(DB_URL); }

    public static void initDB() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT NOT NULL, avatar TEXT)"); 
            stmt.execute("CREATE TABLE IF NOT EXISTS friends (userA TEXT, userB TEXT, status INTEGER, UNIQUE(userA, userB))");
            stmt.execute("CREATE TABLE IF NOT EXISTS chats (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT, receiver TEXT, message TEXT, timestamp INTEGER, read INTEGER DEFAULT 0)");
        } catch (SQLException e) { e.printStackTrace(); }
    }

    static void send(HttpExchange t, String response, int code) throws IOException {
        t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        t.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = t.getResponseBody()) { os.write(bytes); }
        t.close();
    }

    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            File file = new File("index.html");
            if (!file.exists()) { send(t, "index.html missing", 404); return; }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            t.getResponseHeaders().add("Content-Type", "text/html");
            t.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = t.getResponseBody()) { os.write(bytes); }
            t.close();
        }
    }

    // --- NEW: WEBRTC SIGNAL HANDLER ---
    static class SignalHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if(t.getRequestMethod().equalsIgnoreCase("OPTIONS")) { send(t, "", 204); return; }
            String method = t.getRequestMethod();
            String body = readBody(t);
            
            if (method.equalsIgnoreCase("POST")) {
                // SENDING A SIGNAL: "sender:receiver:JSON_DATA"
                String[] parts = body.split(":", 3);
                if (parts.length < 3) { send(t, "Bad Format", 400); return; }
                String receiver = parts[1].toLowerCase().trim();
                String data = parts[2]; // This is the WebRTC JSON (Offer/Answer/Candidate)
                
                // Add to mailbox
                signalMailbox.computeIfAbsent(receiver, k -> new ArrayList<>()).add(data);
                send(t, "Signal Queued", 200);
                
            } else if (method.equalsIgnoreCase("GET")) {
                // CHECKING MAILBOX: "user=myname" (Passed in body for simplicity or query)
                // Actually, let's use the body convention we have: "myname:CHECK"
                String[] parts = body.split(":");
                if (parts.length < 1) return;
                String me = parts[0].toLowerCase().trim();
                
                List<String> signals = signalMailbox.remove(me); // Get and Clear mailbox
                if (signals == null || signals.isEmpty()) {
                    send(t, "", 200);
                } else {
                    // Join all signals with a special delimiter "|||"
                    String combined = String.join("|||", signals);
                    send(t, combined, 200);
                }
            }
        }
    }

    // --- EXISTING HANDLERS ---
    static class AuthHandler implements HttpHandler { String mode; AuthHandler(String m) { this.mode = m; } public void handle(HttpExchange t) throws IOException { if(t.getRequestMethod().equalsIgnoreCase("OPTIONS")) { send(t, "", 204); return; } String body = readBody(t); String[] parts = body.split(":"); if (parts.length < 2) { send(t, "Error", 400); return; } String u = parts[0].trim().toLowerCase(); String p = parts[1].trim(); try (Connection conn = connect()) { if (mode.equals("register")) { PreparedStatement check = conn.prepareStatement("SELECT * FROM users WHERE username = ?"); check.setString(1, u); if (check.executeQuery().next()) { send(t, "User exists", 409); } else { PreparedStatement insert = conn.prepareStatement("INSERT INTO users(username, password) VALUES(?,?)"); insert.setString(1, u); insert.setString(2, p); insert.executeUpdate(); send(t, "OK", 200); } } else { PreparedStatement check = conn.prepareStatement("SELECT * FROM users WHERE username = ? AND password = ?"); check.setString(1, u); check.setString(2, p); if (check.executeQuery().next()) { lastSeen.put(u, System.currentTimeMillis()); send(t, "OK", 200); } else { send(t, "Invalid", 401); } } } catch (SQLException e) { e.printStackTrace(); send(t, "DB Error", 500); } } }
    static class FriendHandler implements HttpHandler { String mode; FriendHandler(String m) { this.mode = m; } public void handle(HttpExchange t) throws IOException { if(t.getRequestMethod().equalsIgnoreCase("OPTIONS")) { send(t, "", 204); return; } String body = readBody(t); String[] parts = body.split(":"); String user = parts[0].toLowerCase().trim(); lastSeen.put(user, System.currentTimeMillis()); try (Connection conn = connect()) { if (mode.equals("list")) { StringBuilder response = new StringBuilder(); long now = System.currentTimeMillis(); String sqlReq = "SELECT u.username, u.avatar FROM friends f JOIN users u ON f.userA = u.username WHERE f.userB = ? AND f.status = 0"; PreparedStatement stmtReq = conn.prepareStatement(sqlReq); stmtReq.setString(1, user); ResultSet rsReq = stmtReq.executeQuery(); while(rsReq.next()) { String f = rsReq.getString("username"); String av = rsReq.getString("avatar"); if (av == null || av.length() < 10) av = "null"; response.append("REQ:").append(f).append("!").append(av).append(","); } String sqlFrd = "SELECT u.username, u.avatar, (SELECT timestamp FROM chats WHERE sender = u.username AND receiver = ? ORDER BY timestamp DESC LIMIT 1) as last_ts, (SELECT message FROM chats WHERE sender = u.username AND receiver = ? ORDER BY timestamp DESC LIMIT 1) as last_txt FROM friends f JOIN users u ON f.userB = u.username WHERE f.userA = ? AND f.status = 1"; PreparedStatement stmtFrd = conn.prepareStatement(sqlFrd); stmtFrd.setString(1, user); stmtFrd.setString(2, user); stmtFrd.setString(3, user); ResultSet rsFrd = stmtFrd.executeQuery(); while (rsFrd.next()) { String f = rsFrd.getString("username"); String av = rsFrd.getString("avatar"); long lastMsgTime = rsFrd.getLong("last_ts"); String lastMsgTxt = rsFrd.getString("last_txt"); if (lastMsgTxt == null) lastMsgTxt = ""; lastMsgTxt = lastMsgTxt.replace("!", "").replace(",", ""); if (av == null || av.length() < 10) av = "null"; boolean isOnline = lastSeen.containsKey(f) && (now - lastSeen.get(f) < ONLINE_TIMEOUT); response.append("FRD:").append(f).append("!").append(isOnline ? "1" : "0").append("!").append(av).append("!").append(lastMsgTime).append("!").append(lastMsgTxt).append(","); } send(t, response.toString(), 200); } else if (mode.equals("request")) { String friend = parts[1].trim().toLowerCase(); if (user.equals(friend)) { send(t, "No self add", 400); return; } PreparedStatement check = conn.prepareStatement("SELECT * FROM friends WHERE (userA=? AND userB=?) OR (userA=? AND userB=?)"); check.setString(1, user); check.setString(2, friend); check.setString(3, friend); check.setString(4, user); if (check.executeQuery().next()) { send(t, "Already connected", 409); return; } PreparedStatement insert = conn.prepareStatement("INSERT INTO friends (userA, userB, status) VALUES (?, ?, 0)"); insert.setString(1, user); insert.setString(2, friend); insert.executeUpdate(); send(t, "Request Sent!", 200); } else if (mode.equals("accept")) { String requester = parts[1].trim().toLowerCase(); PreparedStatement up = conn.prepareStatement("UPDATE friends SET status=1 WHERE userA=? AND userB=?"); up.setString(1, requester); up.setString(2, user); up.executeUpdate(); PreparedStatement ins = conn.prepareStatement("INSERT OR IGNORE INTO friends (userA, userB, status) VALUES (?, ?, 1)"); ins.setString(1, user); ins.setString(2, requester); ins.executeUpdate(); send(t, "Accepted", 200); } } catch (SQLException e) { e.printStackTrace(); send(t, "DB Error", 500); } } }
    static class ChatHandler implements HttpHandler { String mode; ChatHandler(String m) { this.mode = m; } public void handle(HttpExchange t) throws IOException { if(t.getRequestMethod().equalsIgnoreCase("OPTIONS")) { send(t, "", 204); return; } String body = readBody(t); try (Connection conn = connect()) { if (mode.equals("send")) { String[] parts = body.split(":", 3); if(parts.length < 3) return; String s = parts[0].toLowerCase().trim(); String r = parts[1].toLowerCase().trim(); String msg = parts[2]; PreparedStatement check = conn.prepareStatement("SELECT * FROM friends WHERE userA=? AND userB=? AND status=1"); check.setString(1, s); check.setString(2, r); if (!check.executeQuery().next()) { send(t, "Not friends", 403); return; } PreparedStatement stmt = conn.prepareStatement("INSERT INTO chats(sender, receiver, message, timestamp, read) VALUES(?,?,?,?,0)"); stmt.setString(1, s); stmt.setString(2, r); stmt.setString(3, msg); stmt.setLong(4, System.currentTimeMillis()); stmt.executeUpdate(); lastSeen.put(s, System.currentTimeMillis()); send(t, "Sent", 200); } else if (mode.equals("get")) { String[] parts = body.split(":"); String me = parts[0].toLowerCase().trim(); String friend = parts[1].toLowerCase().trim(); lastSeen.put(me, System.currentTimeMillis()); PreparedStatement readStmt = conn.prepareStatement("UPDATE chats SET read=1 WHERE sender=? AND receiver=? AND read=0"); readStmt.setString(1, friend); readStmt.setString(2, me); readStmt.executeUpdate(); String sql = "SELECT sender, message, timestamp, read FROM chats WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?) ORDER BY timestamp ASC"; PreparedStatement stmt = conn.prepareStatement(sql); stmt.setString(1, me); stmt.setString(2, friend); stmt.setString(3, friend); stmt.setString(4, me); ResultSet rs = stmt.executeQuery(); StringBuilder sb = new StringBuilder(); while (rs.next()) { sb.append(rs.getString("sender")).append(":").append(rs.getLong("timestamp")).append(":").append(rs.getInt("read")).append(":").append(rs.getString("message")).append("|"); } send(t, sb.toString(), 200); } } catch (SQLException e) { e.printStackTrace(); send(t, "DB Error", 500); } } }
    static class AvatarHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(t.getRequestMethod().equalsIgnoreCase("OPTIONS")) { send(t, "", 204); return; } String body = readBody(t); String[] parts = body.split(":", 2); if (parts.length < 2) return; String user = parts[0].toLowerCase().trim(); String data = parts[1].trim(); try (Connection conn = connect()) { if (data.equals("READ")) { PreparedStatement stmt = conn.prepareStatement("SELECT avatar FROM users WHERE username = ?"); stmt.setString(1, user); ResultSet rs = stmt.executeQuery(); if (rs.next()) { send(t, (rs.getString("avatar") == null ? "null" : rs.getString("avatar")), 200); } else { send(t, "null", 200); } } else { PreparedStatement stmt = conn.prepareStatement("UPDATE users SET avatar = ? WHERE username = ?"); stmt.setString(1, data); stmt.setString(2, user); stmt.executeUpdate(); send(t, "Saved", 200); } } catch (SQLException e) { e.printStackTrace(); send(t, "Error", 500); } } }
    static class SearchHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(t.getRequestMethod().equalsIgnoreCase("OPTIONS")) { send(t, "", 204); return; } String body = readBody(t); String[] parts = body.split(":"); if(parts.length < 2) return; String me = parts[0].toLowerCase().trim(); String query = parts[1].toLowerCase().trim() + "%"; try (Connection conn = connect()) { String sql = "SELECT username, avatar FROM users WHERE username LIKE ? AND username != ? LIMIT 5"; PreparedStatement stmt = conn.prepareStatement(sql); stmt.setString(1, query); stmt.setString(2, me); ResultSet rs = stmt.executeQuery(); StringBuilder sb = new StringBuilder(); while(rs.next()) { String u = rs.getString("username"); String a = rs.getString("avatar"); if(a == null || a.length() < 10) a = "null"; sb.append(u).append("!").append(a).append("|"); } send(t, sb.toString(), 200); } catch (SQLException e) { e.printStackTrace(); send(t, "", 500); } } }
    static class TypingHandler implements HttpHandler { public void handle(HttpExchange t) throws IOException { if(t.getRequestMethod().equalsIgnoreCase("OPTIONS")) { send(t, "", 204); return; } String body = readBody(t); String[] parts = body.split(":"); if(parts.length < 3) return; String me = parts[0]; String friend = parts[1]; String action = parts[2]; if (action.equals("TYPE")) { typingMap.put(me + ":" + friend, System.currentTimeMillis()); send(t, "OK", 200); } else if (action.equals("CHECK")) { String key = friend + ":" + me; boolean isTyping = typingMap.containsKey(key) && (System.currentTimeMillis() - typingMap.get(key) < 3000); send(t, isTyping ? "1" : "0", 200); } } }
    
    static String readBody(HttpExchange t) { try (Scanner s = new Scanner(t.getRequestBody()).useDelimiter("\\A")) { return s.hasNext() ? s.next() : ""; } catch (Exception e) { return ""; } }
}
