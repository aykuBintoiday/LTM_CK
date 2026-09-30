package server;

import common.Protocol;
import common.ModernUI;
import server.DatabaseManager.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

public class ServerAdminGUI extends JFrame {
    private static final int DEFAULT_UDP_PORT = 5000;
    private static final int DEFAULT_TCP_PORT = 5001;
    private static final int BUFFER_SIZE = 65507;
    private static final long OFFLINE_TIMEOUT_MS = 12_000;
    private static final long FILE_TRANSFER_TIMEOUT_MS = 120_000;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private final Color NAVY = ModernUI.NAVY;
    private final Color BLUE = ModernUI.BLUE;
    private final Color GREEN = ModernUI.GREEN;
    private final Color RED = ModernUI.RED;
    private final Color BG = ModernUI.BG;

    private final JTextField txtServerIp = new JTextField(14);
    private final JTextField txtUdpPort = new JTextField(String.valueOf(DEFAULT_UDP_PORT), 6);
    private final JTextField txtTcpPort = new JTextField(String.valueOf(DEFAULT_TCP_PORT), 6);
    private final JTextField txtDbHost = new JTextField("127.0.0.1", 10);
    private final JTextField txtDbPort = new JTextField("3306", 5);
    private final JTextField txtDbName = new JTextField("smart_classroom", 12);
    private final JTextField txtDbUser = new JTextField("root", 8);
    private final JPasswordField txtDbPassword = new JPasswordField(9);
    private final JTextField txtSmtpEmail = new JTextField(20);
    private final JPasswordField txtSmtpAppPassword = new JPasswordField(16);
    private final JButton btnStart = new JButton("Khởi động Server");
    private final JLabel lblStatus = new JLabel("ĐÃ DỪNG");
    private final JLabel lblDb = new JLabel("Chưa kết nối DB");
    private final JLabel lblEmail = new JLabel("Email gửi OTP: chưa cấu hình");
    private final JLabel lblUsers = new JLabel("0");
    private final JLabel lblOnline = new JLabel("0");
    private final JLabel lblClasses = new JLabel("0");
    private final JLabel lblSessions = new JLabel("0");
    private final JTextArea logArea = new JTextArea();

    private final DefaultTableModel pendingModel = nonEditable(new Object[]{"ID","Họ và tên","SĐT","Email","Vai trò yêu cầu","Ngày đăng ký"});
    private final JTable pendingTable = new JTable(pendingModel);
    private final DefaultTableModel accountModel = nonEditable(new Object[]{"ID","Họ và tên","Email","Vai trò","Trạng thái","Đăng nhập gần nhất"});
    private final JTable accountTable = new JTable(accountModel);
    private final DefaultTableModel onlineModel = nonEditable(new Object[]{"Họ và tên","Email","Vai trò","IP","UDP Port","Buổi học","Hoạt động"});
    private final JTable onlineTable = new JTable(onlineModel);
    private final DefaultTableModel classModel = nonEditable(new Object[]{"ID","Tên lớp","Mã lớp","Giáo viên","Sinh viên","Session đang mở"});
    private final JTable classTable = new JTable(classModel);
    private final DefaultTableModel sessionModel = nonEditable(new Object[]{"Session","Lớp","Mã lớp","Giáo viên","Bắt đầu","Online"});
    private final JTable sessionTable = new JTable(sessionModel);

    private volatile boolean running;
    private DatagramSocket udpSocket;
    private ServerSocket tcpServer;
    private Thread udpThread;
    private Thread tcpThread;
    private int udpPort = DEFAULT_UDP_PORT;
    private int tcpPort = DEFAULT_TCP_PORT;
    private DatabaseManager db;
    private EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> resetRequestTimes = new ConcurrentHashMap<>();

    private final ExecutorService tcpWorkers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, AuthSession> authTokens = new ConcurrentHashMap<>();
    private final Map<String, OnlineClient> onlineByEmail = new ConcurrentHashMap<>();
    private final Map<String, String> endpointToEmail = new ConcurrentHashMap<>();
    private final Map<String, FileTransfer> fileTransfers = new ConcurrentHashMap<>();
    private final DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            ModernUI.installGlobalDefaults();
            new ServerAdminGUI().setVisible(true);
        });
    }

    public ServerAdminGUI() {
        setTitle("Smart Classroom V3.11 - Server Control Center");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(1150, 720));
        setLocationRelativeTo(null);
        txtServerIp.setText(findLanIp());
        buildUI();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (JOptionPane.showConfirmDialog(ServerAdminGUI.this, "Dừng Server và thoát?", "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    stopServer("Ứng dụng Server đã đóng");
                    scheduler.shutdownNow();
                    tcpWorkers.shutdownNow();
                    dispose();
                }
            }
        });
        scheduler.scheduleAtFixedRate(this::removeOfflineClients, 4, 4, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> SwingUtilities.invokeLater(this::refreshAll), 2, 2, TimeUnit.SECONDS);
    }

    private static DefaultTableModel nonEditable(Object[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private void buildUI() {
        VerticalPage root = new VerticalPage(new BorderLayout(0,14));
        root.setBackground(ModernUI.BG);
        root.setBorder(new EmptyBorder(16,18,18,18));
        root.setPreferredSize(new Dimension(1160,860));

        JScrollPane pageScroll = new JScrollPane(root,JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pageScroll.setBorder(BorderFactory.createEmptyBorder());
        pageScroll.getViewport().setBackground(ModernUI.BG);
        pageScroll.getVerticalScrollBar().setUnitIncrement(24);
        pageScroll.getVerticalScrollBar().setBlockIncrement(150);
        pageScroll.getVerticalScrollBar().setPreferredSize(new Dimension(10,0));
        setContentPane(pageScroll);

        JPanel north = new JPanel();north.setOpaque(false);north.setLayout(new BoxLayout(north,BoxLayout.Y_AXIS));

        ModernUI.GradientPanel hero = new ModernUI.GradientPanel(ModernUI.NAVY,new Color(30,64,175),22);
        hero.setLayout(new BorderLayout(18,0));hero.setBorder(new EmptyBorder(18,22,18,22));hero.setMaximumSize(new Dimension(Integer.MAX_VALUE,92));
        JPanel brand = new JPanel();brand.setOpaque(false);brand.setLayout(new BoxLayout(brand,BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SMART CLASSROOM · SERVER CONTROL CENTER");title.setForeground(Color.WHITE);title.setFont(new Font("Segoe UI",Font.BOLD,22));
        JLabel subtitle = new JLabel("TCP + UDP + MySQL + WebRTC signaling · quản trị tập trung");subtitle.setForeground(new Color(219,234,254));subtitle.setFont(new Font("Segoe UI",Font.PLAIN,12));
        brand.add(title);brand.add(Box.createVerticalStrut(4));brand.add(subtitle);hero.add(brand,BorderLayout.WEST);

        JPanel serverActions = new JPanel(new FlowLayout(FlowLayout.RIGHT,10,0));serverActions.setOpaque(false);
        lblDb.setForeground(new Color(203,213,225));lblDb.setFont(new Font("Segoe UI",Font.BOLD,11));
        lblEmail.setForeground(new Color(253,230,138));lblEmail.setFont(new Font("Segoe UI",Font.BOLD,11));
        lblStatus.setForeground(new Color(252,165,165));lblStatus.setFont(new Font("Segoe UI",Font.BOLD,12));
        btnStart.setText("KHỞI ĐỘNG SERVER");ModernUI.styleButton(btnStart,ModernUI.ButtonKind.SUCCESS);btnStart.setPreferredSize(new Dimension(190,42));
        serverActions.add(lblDb);serverActions.add(lblEmail);serverActions.add(lblStatus);serverActions.add(btnStart);hero.add(serverActions,BorderLayout.EAST);
        north.add(hero);north.add(Box.createVerticalStrut(12));

        ModernUI.CardPanel cfgCard = new ModernUI.CardPanel();cfgCard.setLayout(new BorderLayout(0,10));cfgCard.setBorder(new EmptyBorder(14,16,14,16));
        JLabel cfgTitle=ModernUI.title("Cấu hình máy chủ",15,ModernUI.TEXT);cfgCard.add(cfgTitle,BorderLayout.NORTH);
        JPanel cfgRows=new JPanel();cfgRows.setOpaque(false);cfgRows.setLayout(new BoxLayout(cfgRows,BoxLayout.Y_AXIS));
        JPanel netRow=configRow();addCfg(netRow,"IP LAN",txtServerIp,220);addCfg(netRow,"UDP",txtUdpPort,105);addCfg(netRow,"TCP",txtTcpPort,105);
        JPanel dbRow=configRow();addCfg(dbRow,"MySQL",txtDbHost,170);addCfg(dbRow,"Port",txtDbPort,100);addCfg(dbRow,"Database",txtDbName,190);addCfg(dbRow,"User",txtDbUser,145);addCfg(dbRow,"Password",txtDbPassword,165);
        JPanel mailRow=configRow();addCfg(mailRow,"Gmail gửi OTP",txtSmtpEmail,285);addCfg(mailRow,"App Password",txtSmtpAppPassword,250);JLabel hint=new JLabel("OTP được gửi tới email của Client");hint.setForeground(ModernUI.MUTED);hint.setFont(new Font("Segoe UI",Font.ITALIC,11));mailRow.add(Box.createHorizontalStrut(4));mailRow.add(hint);mailRow.add(Box.createHorizontalGlue());
        cfgRows.add(netRow);cfgRows.add(Box.createVerticalStrut(5));cfgRows.add(dbRow);cfgRows.add(Box.createVerticalStrut(5));cfgRows.add(mailRow);cfgCard.add(cfgRows,BorderLayout.CENTER);north.add(cfgCard);
        root.add(north,BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0,12));body.setOpaque(false);
        JPanel stats = new JPanel(new GridLayout(1,4,12,0));stats.setOpaque(false);
        stats.add(stat("TỔNG TÀI KHOẢN", "Users", lblUsers, new Color(239,246,255), ModernUI.BLUE));
        stats.add(stat("ĐANG ONLINE", "Live", lblOnline, new Color(240,253,244), ModernUI.GREEN));
        stats.add(stat("LỚP HỌC", "Classes", lblClasses, new Color(255,247,237), ModernUI.AMBER));
        stats.add(stat("BUỔI ĐANG MỞ", "Sessions", lblSessions, new Color(250,245,255), new Color(126,34,206)));
        body.add(stats,BorderLayout.NORTH);

        ModernUI.CardPanel dataCard=new ModernUI.CardPanel();dataCard.setLayout(new BorderLayout());dataCard.setBorder(new EmptyBorder(8,10,10,10));
        JTabbedPane tabs = new JTabbedPane();ModernUI.styleTabs(tabs);
        tabs.addTab("Chờ duyệt", pendingPanel());
        tabs.addTab("Tài khoản", accountPanel());
        tabs.addTab("Online", tablePanel(onlineTable));
        tabs.addTab("Lớp học", tablePanel(classTable));
        tabs.addTab("Buổi học", tablePanel(sessionTable));
        logArea.setEditable(false);logArea.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));logArea.setBackground(new Color(15,23,42));logArea.setForeground(new Color(203,213,225));logArea.setCaretColor(Color.WHITE);logArea.setBorder(new EmptyBorder(12,12,12,12));
        JScrollPane logScroll=ModernUI.scroll(logArea);logScroll.setBorder(new ModernUI.RoundedBorder(new Color(51,65,85),14,1));tabs.addTab("Nhật ký",logScroll);
        dataCard.add(tabs);body.add(dataCard,BorderLayout.CENTER);root.add(body,BorderLayout.CENTER);

        btnStart.addActionListener(e -> { if (running) stopServer("Dừng bởi quản trị viên"); else startServer(); });
        txtDbPassword.addActionListener(e -> { if (!running) startServer(); });
    }

    private JPanel configRow() {
        JPanel row=new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row,BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,52));
        return row;
    }

    private void addCfg(JPanel p, String name, JComponent c, int width) {
        JLabel l = new JLabel(name);l.setForeground(ModernUI.MUTED);l.setFont(new Font("Segoe UI",Font.BOLD,11));
        p.add(l);p.add(Box.createHorizontalStrut(7));
        ModernUI.styleInput(c);
        Dimension d=new Dimension(width,ModernUI.INPUT_HEIGHT);
        c.setPreferredSize(d);c.setMinimumSize(d);c.setMaximumSize(d);
        p.add(c);p.add(Box.createHorizontalStrut(12));
    }

    private JPanel stat(String name, String meta, JLabel value, Color soft, Color accent) {
        ModernUI.CardPanel p=new ModernUI.CardPanel();p.setLayout(new BorderLayout(10,0));p.setBorder(new EmptyBorder(14,16,14,16));
        ModernUI.CardPanel badge=new ModernUI.CardPanel(14,soft,soft,false);badge.setLayout(new GridBagLayout());badge.setPreferredSize(new Dimension(46,46));JLabel b=new JLabel(meta.substring(0,1));b.setFont(new Font("Segoe UI",Font.BOLD,17));b.setForeground(accent);badge.add(b);p.add(badge,BorderLayout.WEST);
        JPanel text=new JPanel();text.setOpaque(false);text.setLayout(new BoxLayout(text,BoxLayout.Y_AXIS));JLabel n=new JLabel(name);n.setForeground(ModernUI.MUTED);n.setFont(new Font("Segoe UI",Font.BOLD,10));value.setFont(new Font("Segoe UI",Font.BOLD,26));value.setForeground(ModernUI.TEXT);text.add(n);text.add(Box.createVerticalStrut(2));text.add(value);p.add(text,BorderLayout.CENTER);return p;
    }

    private JPanel tablePanel(JTable table) {
        JPanel p=new JPanel(new BorderLayout());p.setOpaque(false);ModernUI.styleTable(table);p.add(ModernUI.scroll(table));return p;
    }

    private JPanel pendingPanel() {
        JPanel p=tablePanel(pendingTable);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,8));actions.setOpaque(false);
        JButton requested=new JButton("Duyệt theo yêu cầu"),teacher=new JButton("Duyệt GIÁO VIÊN"),student=new JButton("Duyệt HỌC SINH"),reject=new JButton("Từ chối");
        ModernUI.styleCompactButton(requested,ModernUI.ButtonKind.SECONDARY);ModernUI.styleCompactButton(teacher,ModernUI.ButtonKind.PRIMARY);ModernUI.styleCompactButton(student,ModernUI.ButtonKind.SUCCESS);ModernUI.styleCompactButton(reject,ModernUI.ButtonKind.DANGER);
        requested.addActionListener(e -> approveSelected(null));teacher.addActionListener(e -> approveSelected("TEACHER"));student.addActionListener(e -> approveSelected("STUDENT"));reject.addActionListener(e -> rejectSelected());
        actions.add(requested);actions.add(teacher);actions.add(student);actions.add(reject);p.add(actions,BorderLayout.SOUTH);return p;
    }

    private JPanel accountPanel() {
        JPanel p=tablePanel(accountTable);JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,8));actions.setOpaque(false);
        JButton lock=new JButton("Khóa tài khoản"),unlock=new JButton("Mở khóa");ModernUI.styleCompactButton(unlock,ModernUI.ButtonKind.SUCCESS);ModernUI.styleCompactButton(lock,ModernUI.ButtonKind.DANGER);
        lock.addActionListener(e -> setSelectedAccountStatus("LOCKED"));unlock.addActionListener(e -> setSelectedAccountStatus("ACTIVE"));actions.add(unlock);actions.add(lock);p.add(actions,BorderLayout.SOUTH);return p;
    }

    private void startServer() {
        try {
            udpPort = Integer.parseInt(txtUdpPort.getText().trim()); tcpPort = Integer.parseInt(txtTcpPort.getText().trim());
            if (udpPort < 1024 || udpPort > 65535 || tcpPort < 1024 || tcpPort > 65535 || udpPort == tcpPort) throw new IllegalArgumentException("UDP/TCP port phải khác nhau và nằm trong 1024-65535");
            db = new DatabaseManager(txtDbHost.getText().trim(), Integer.parseInt(txtDbPort.getText().trim()), txtDbName.getText().trim(), txtDbUser.getText().trim(), new String(txtDbPassword.getPassword()));
            db.initialize();
            lblDb.setText("MySQL: ĐÃ KẾT NỐI"); lblDb.setForeground(new Color(120,235,165));
            emailService = new EmailService(txtSmtpEmail.getText().trim(), new String(txtSmtpAppPassword.getPassword()));
            if (emailService.isConfigured()) {
                lblEmail.setText("Email gửi OTP: SẴN SÀNG");
                lblEmail.setForeground(new Color(120,235,165));
            } else {
                lblEmail.setText("Email gửi OTP: CHƯA CẤU HÌNH");
                lblEmail.setForeground(new Color(255,220,140));
            }

            udpSocket = new DatagramSocket(null); udpSocket.setReuseAddress(true); udpSocket.bind(new InetSocketAddress("0.0.0.0", udpPort));
            tcpServer = new ServerSocket(); tcpServer.setReuseAddress(true); tcpServer.bind(new InetSocketAddress("0.0.0.0", tcpPort));
            running = true;
            udpThread = new Thread(this::udpReceiveLoop, "udp-server"); udpThread.setDaemon(true); udpThread.start();
            tcpThread = new Thread(this::tcpAcceptLoop, "tcp-server"); tcpThread.setDaemon(true); tcpThread.start();
            setConfigEnabled(false); btnStart.setText("DỪNG SERVER"); ModernUI.styleButton(btnStart,ModernUI.ButtonKind.DANGER); btnStart.setPreferredSize(new Dimension(190,42)); lblStatus.setText("ĐANG CHẠY"); lblStatus.setForeground(new Color(134,239,172));
            log("START TCP=" + tcpPort + " UDP=" + udpPort + " | IP Client: " + txtServerIp.getText().trim()); refreshAll();
        } catch (Exception ex) {
            lblDb.setText("DB/SERVER lỗi"); lblDb.setForeground(new Color(255,190,190));
            JOptionPane.showMessageDialog(this, ex.getMessage() + "\n\nNếu thiếu MySQL Connector, hãy chạy SETUP_LIBS.bat.", "Không thể khởi động", JOptionPane.ERROR_MESSAGE);
            log("START ERROR: " + ex);
            cleanupSockets();
        }
    }

    private void stopServer(String reason) {
        if (!running) { cleanupSockets(); return; }
        running = false;
        broadcastAll("SERVER_SHUTDOWN|" + Protocol.enc(reason));
        cleanupSockets(); authTokens.clear(); onlineByEmail.clear(); endpointToEmail.clear();
        setConfigEnabled(true); btnStart.setText("KHỞI ĐỘNG SERVER"); ModernUI.styleButton(btnStart,ModernUI.ButtonKind.SUCCESS); btnStart.setPreferredSize(new Dimension(190,42)); lblStatus.setText("ĐÃ DỪNG"); lblStatus.setForeground(new Color(252,165,165)); log("STOP: " + reason); refreshAll();
    }

    private void cleanupSockets() {
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        try { if (tcpServer != null) tcpServer.close(); } catch (Exception ignored) {}
        udpSocket = null; tcpServer = null;
    }

    private void setConfigEnabled(boolean enabled) {
        for (JComponent c : List.of(txtServerIp,txtUdpPort,txtTcpPort,txtDbHost,txtDbPort,txtDbName,txtDbUser,txtDbPassword,txtSmtpEmail,txtSmtpAppPassword)) c.setEnabled(enabled);
    }

    private void tcpAcceptLoop() {
        while (running) {
            try {
                Socket s = tcpServer.accept();
                tcpWorkers.submit(() -> handleTcpClient(s));
            } catch (SocketException e) { break; }
            catch (Exception e) { if (running) log("TCP ACCEPT ERROR: " + e.getMessage()); }
        }
    }

    private void handleTcpClient(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(8000);
            String request = in.readLine();
            String response = handleTcpRequest(request == null ? "" : request.trim());
            out.write(response); out.newLine(); out.flush();
        } catch (Exception e) { log("TCP CLIENT ERROR: " + e.getMessage()); }
    }

    private String handleTcpRequest(String raw) {
        String[] p = raw.split("\\|", -1);
        String cmd = p.length == 0 ? "" : p[0].toUpperCase(Locale.ROOT);
        try {
            return switch (cmd) {
                case "PING" -> "PONG";
                case "REGISTER" -> tcpRegister(p);
                case "LOGIN" -> tcpLogin(p);
                case "FORGOT_PASSWORD" -> tcpForgotPassword(p);
                case "VERIFY_RESET_OTP" -> tcpVerifyResetOtp(p);
                case "RESET_PASSWORD" -> tcpResetPassword(p);
                case "LIST_MY_CLASSES" -> tcpListMyClasses(p);
                case "LIST_AVAILABLE_CLASSES" -> tcpListAvailable(p);
                case "CREATE_CLASS" -> tcpCreateClass(p);
                case "ENROLL_CLASS" -> tcpEnroll(p);
                case "OPEN_SESSION" -> tcpOpenSession(p);
                case "CLOSE_SESSION" -> tcpCloseSession(p);
                case "CLASS_ROSTER" -> tcpClassRoster(p);
                case "ATTENDANCE_HISTORY" -> tcpAttendanceHistory(p);
                case "ATTENDANCE_DETAIL" -> tcpAttendanceDetail(p);
                case "LOGOUT" -> { if (p.length > 1) authTokens.remove(p[1]); yield "LOGOUT_OK"; }
                default -> "ERROR|" + Protocol.enc("Lệnh TCP không hợp lệ: " + cmd);
            };
        } catch (Exception e) {
            return "ERROR|" + Protocol.enc(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String tcpRegister(String[] p) throws Exception {
        if (p.length < 6) return "ERROR|" + Protocol.enc("Thiếu thông tin đăng ký");
        RegisterResult r = db.register(Protocol.dec(p[1]), Protocol.dec(p[2]), Protocol.dec(p[3]), Protocol.dec(p[4]), p[5]);
        log("REGISTER " + Protocol.dec(p[3]) + " -> " + r.message());
        return (r.ok() ? "REGISTER_OK|" : "ERROR|") + Protocol.enc(r.message());
    }

    private String tcpLogin(String[] p) throws Exception {
        if (p.length < 3) return "ERROR|" + Protocol.enc("Thiếu email hoặc mật khẩu");
        String email = Protocol.dec(p[1]);
        LoginResult r = db.login(email, Protocol.dec(p[2]));
        if (!r.ok()) {
            String msg = switch (r.code()) {
                case "NOT_FOUND" -> "Tài khoản chưa tồn tại. Vui lòng đăng ký trước.";
                case "WRONG_PASSWORD" -> "Email hoặc mật khẩu không đúng.";
                case "PENDING" -> "Tài khoản đang chờ Server duyệt vai trò.";
                case "REJECTED" -> "Tài khoản đã bị từ chối.";
                case "LOCKED" -> "Tài khoản đang bị khóa.";
                default -> "Không thể đăng nhập: " + r.code();
            };
            return "LOGIN_FAIL|" + r.code() + "|" + Protocol.enc(msg);
        }
        UserRecord u = r.user();
        String token = UUID.randomUUID().toString();
        authTokens.put(token, new AuthSession(token,u.id(),u.fullName(),u.email(),u.role(),System.currentTimeMillis()));
        log("LOGIN TCP " + u.email() + " [" + u.role() + "]");
        return "LOGIN_OK|" + token + "|" + u.id() + "|" + Protocol.enc(u.fullName()) + "|" + u.role() + "|" + Protocol.enc(u.email());
    }

    private String tcpForgotPassword(String[] p) throws Exception {
        if (p.length < 2) return "ERROR|" + Protocol.enc("Thiếu email");
        if (emailService == null || !emailService.isConfigured()) {
            return "ERROR|" + Protocol.enc("Server chưa cấu hình Gmail hệ thống (NGƯỜI GỬI OTP). Hãy nhập Gmail và App Password của Gmail gửi trên Server rồi khởi động lại.");
        }
        String email = Protocol.dec(p[1]).trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long last = resetRequestTimes.getOrDefault(email, 0L);
        long waitMs = 60_000 - (now - last);
        if (waitMs > 0) return "ERROR|" + Protocol.enc("Vui lòng chờ " + Math.max(1, (waitMs + 999) / 1000) + " giây trước khi gửi lại mã.");

        Optional<UserRecord> ou = db.findUserByEmail(email);
        if (ou.isEmpty()) return "ERROR|" + Protocol.enc("Email này chưa được đăng ký trong hệ thống.");

        // Luôn lấy địa chỉ người nhận từ bản ghi tài khoản trong Database.
        // Client chỉ nhập email để xác định tài khoản; OTP không được gửi về email cấu hình trên Server.
        String recipientEmail = ou.get().email().trim().toLowerCase(Locale.ROOT);
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        ResetRequestResult rr = db.createPasswordReset(recipientEmail, code, 5);
        if (!rr.ok()) return "ERROR|" + Protocol.enc(rr.message());
        try {
            emailService.sendPasswordResetCode(recipientEmail, rr.user().fullName(), code, 5);
            resetRequestTimes.put(email, now);
            log("PASSWORD_RESET OTP sent to CLIENT email " + maskEmail(recipientEmail));
            return "FORGOT_OK|" + Protocol.enc("Mã OTP 6 số đã được gửi tới email tài khoản của bạn: " + maskEmail(recipientEmail) + ". Mã có hiệu lực 5 phút.");
        } catch (Exception e) {
            log("PASSWORD_RESET EMAIL ERROR " + email + ": " + e.getMessage());
            return "ERROR|" + Protocol.enc("Không gửi được email OTP: " + e.getMessage());
        }
    }

    private String tcpVerifyResetOtp(String[] p) throws Exception {
        if (p.length < 3) return "ERROR|" + Protocol.enc("Thiếu email hoặc mã xác minh");
        String email = Protocol.dec(p[1]).trim().toLowerCase(Locale.ROOT);
        String code = Protocol.dec(p[2]).trim();
        ResetVerifyResult r = db.verifyPasswordResetOtp(email, code);
        if (!r.ok()) return "OTP_FAIL|" + r.code() + "|" + Protocol.enc(r.message());
        log("PASSWORD_RESET OTP verified for " + email);
        return "OTP_OK|" + r.resetToken() + "|" + Protocol.enc(r.message());
    }

    private String tcpResetPassword(String[] p) throws Exception {
        if (p.length < 3) return "ERROR|" + Protocol.enc("Thiếu phiên đặt lại hoặc mật khẩu mới");
        String resetToken = p[1];
        String newPassword = Protocol.dec(p[2]);
        ResetPasswordResult r = db.resetPassword(resetToken, newPassword);
        if (!r.ok()) return "ERROR|" + Protocol.enc(r.message());
        log("PASSWORD_RESET completed");
        return "RESET_OK|" + Protocol.enc(r.message());
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.substring(0, 1) + "***" + (local.length() > 2 ? local.substring(local.length() - 1) : "") + domain;
    }

    private String tcpListMyClasses(String[] p) throws Exception {
        AuthSession a = requireToken(p,1);
        List<ClassRecord> list = "TEACHER".equals(a.role) ? db.teacherClasses(a.userId) : db.studentClasses(a.userId);
        return "CLASSES|" + packClasses(list);
    }
    private String tcpListAvailable(String[] p) throws Exception {
        AuthSession a = requireToken(p,1); if (!"STUDENT".equals(a.role)) throw new IllegalArgumentException("Chỉ học sinh mới đăng ký lớp");
        return "CLASSES|" + packClasses(db.availableClasses(a.userId));
    }
    private String packClasses(List<ClassRecord> list) {
        List<String[]> rows = new ArrayList<>();
        for (ClassRecord c : list) rows.add(new String[]{String.valueOf(c.id()),c.className(),c.classCode(),c.teacherName(),String.valueOf(c.studentCount()),String.valueOf(c.activeSessionId()),c.status()});
        return Protocol.packRows(rows);
    }
    private String tcpCreateClass(String[] p) throws Exception {
        AuthSession a = requireToken(p,1); if (!"TEACHER".equals(a.role)) throw new IllegalArgumentException("Chỉ GIÁO VIÊN mới được tạo lớp");
        if (p.length < 4) throw new IllegalArgumentException("Thiếu tên lớp hoặc mã lớp");
        ClassRecord c = db.createClass(a.userId, Protocol.dec(p[2]), Protocol.dec(p[3]));
        log("CLASS " + c.classCode() + " created by " + a.email);
        return "CLASS_CREATED|" + c.id() + "|" + Protocol.enc(c.className()) + "|" + Protocol.enc(c.classCode());
    }
    private String tcpEnroll(String[] p) throws Exception {
        AuthSession a = requireToken(p,1); if (!"STUDENT".equals(a.role)) throw new IllegalArgumentException("Chỉ HỌC SINH mới đăng ký lớp");
        if (p.length < 3) throw new IllegalArgumentException("Thiếu ID lớp");
        db.enrollStudent(a.userId, Long.parseLong(p[2]));
        log("ENROLL " + a.email + " -> class " + p[2]);
        return "ENROLL_OK";
    }
    private String tcpOpenSession(String[] p) throws Exception {
        AuthSession a = requireToken(p,1); if (!"TEACHER".equals(a.role)) throw new IllegalArgumentException("Chỉ GIÁO VIÊN mới mở buổi học");
        SessionRecord s = db.openSession(a.userId, Long.parseLong(p[2]));
        return "SESSION_OPENED|" + s.id() + "|" + s.classId() + "|" + Protocol.enc(s.className()) + "|" + Protocol.enc(s.classCode()) + "|" + Protocol.enc(s.teacherName());
    }
    private String tcpCloseSession(String[] p) throws Exception {
        AuthSession a = requireToken(p,1);
        long sessionId = Long.parseLong(p[2]);
        db.closeSession(a.userId, sessionId);
        closeRuntimeSession(sessionId, "Giáo viên đã kết thúc buổi học");
        return "SESSION_CLOSED";
    }
    private String tcpClassRoster(String[] p) throws Exception {
        requireToken(p,1); List<RosterStudent> roster = db.classRoster(Long.parseLong(p[2])); List<String[]> rows = new ArrayList<>();
        for (RosterStudent s : roster) rows.add(new String[]{String.valueOf(s.id()),s.fullName(),s.email(),s.phone()});
        return "ROSTER|" + Protocol.packRows(rows);
    }
    private String tcpAttendanceHistory(String[] p) throws Exception {
        requireToken(p,1); List<String[]> rows = new ArrayList<>();
        for (AttendanceSummary a : db.attendanceHistory(Long.parseLong(p[2]))) rows.add(new String[]{String.valueOf(a.attendanceId()),String.valueOf(a.sessionId()),DatabaseManager.displayTime(a.takenAt()),String.valueOf(a.presentCount()),String.valueOf(a.absentCount())});
        return "ATTENDANCE_HISTORY|" + Protocol.packRows(rows);
    }
    private String tcpAttendanceDetail(String[] p) throws Exception {
        requireToken(p,1); List<String[]> rows = new ArrayList<>();
        for (AttendanceItem a : db.attendanceDetail(Long.parseLong(p[2]))) rows.add(new String[]{String.valueOf(a.studentId()),a.fullName(),a.email(),a.status(),DatabaseManager.displayTime(a.recordedAt())});
        return "ATTENDANCE_DETAIL|" + Protocol.packRows(rows);
    }

    private AuthSession requireToken(String[] p, int idx) {
        if (p.length <= idx) throw new IllegalArgumentException("Thiếu phiên đăng nhập");
        AuthSession a = authTokens.get(p[idx]);
        if (a == null) throw new IllegalArgumentException("Phiên đăng nhập hết hạn. Vui lòng đăng nhập lại.");
        a.lastSeen = System.currentTimeMillis(); return a;
    }

    private void udpReceiveLoop() {
        while (running) {
            try {
                byte[] buf = new byte[BUFFER_SIZE]; DatagramPacket packet = new DatagramPacket(buf,buf.length); udpSocket.receive(packet);
                String raw = new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8).trim();
                handleUdp(packet.getAddress(), packet.getPort(), raw);
            } catch (SocketException e) { break; }
            catch (Exception e) { if (running) log("UDP RECEIVE ERROR: " + e.getMessage()); }
        }
    }

    private void handleUdp(InetAddress address, int port, String raw) {
        String[] p = raw.split("\\|",-1); String cmd = p.length == 0 ? "" : p[0].toUpperCase(Locale.ROOT);
        try {
            switch (cmd) {
                case "UDP_AUTH" -> udpAuth(address,port,p);
                case "HEARTBEAT" -> heartbeat(address,port);
                case "JOIN_SESSION" -> joinSession(address,port,p);
                case "LEAVE_SESSION" -> leaveSession(requireOnline(address,port), true);
                case "CHAT" -> chat(address,port,p);
                case "RAISE_HAND" -> raiseHand(address,port);
                case "ATTENDANCE_START" -> takeAttendance(address,port);
                case "MEDIA_STATE" -> mediaState(address,port,p);
                case "SCREEN_STATE" -> screenState(address,port,p);
                case "FILE_START" -> fileStart(address,port,p);
                case "FILE_CHUNK" -> fileChunk(address,port,p);
                case "FILE_END" -> fileEnd(address,port,p);
                case "FILE_MISSING" -> fileMissing(address,port,p);
                case "FILE_RECEIVED" -> fileReceived(address,port,p);
                case "RTC_OFFER","RTC_ANSWER","RTC_ICE" -> relayRtc(address,port,p,cmd);
                case "LOGOUT" -> logout(address,port);
                default -> send(address,port,"ERROR|" + Protocol.enc("Lệnh UDP không hợp lệ: " + cmd));
            }
        } catch (Exception e) {
            send(address,port,"ERROR|" + Protocol.enc(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void udpAuth(InetAddress a, int port, String[] p) {
        if (p.length < 2) { send(a,port,"ERROR|"+Protocol.enc("Thiếu token")); return; }
        AuthSession auth = authTokens.get(p[1]);
        if (auth == null) { send(a,port,"ERROR|"+Protocol.enc("Phiên đăng nhập không hợp lệ")); return; }
        OnlineClient old = onlineByEmail.get(auth.email);
        if (old != null) endpointToEmail.remove(endpointKey(old.address,old.port));
        OnlineClient c = new OnlineClient(auth.userId,auth.fullName,auth.email,auth.role,a,port);
        onlineByEmail.put(auth.email,c); endpointToEmail.put(endpointKey(a,port),auth.email);
        send(c,"UDP_AUTH_OK|" + Protocol.enc(c.fullName) + "|" + c.role + "|" + Protocol.enc(c.email));
        log("UDP ONLINE " + c.email + " from " + a.getHostAddress() + ":" + port); refreshAllAsync();
    }
    private void heartbeat(InetAddress a,int port) { OnlineClient c = findOnline(a,port); if (c != null) c.lastSeen = System.currentTimeMillis(); }

    private void joinSession(InetAddress a,int port,String[] p) throws Exception {
        OnlineClient c = requireOnline(a,port); if (p.length < 2) throw new IllegalArgumentException("Thiếu session ID"); long sessionId = Long.parseLong(p[1]);
        if (!db.canJoinSession(c.userId,c.role,sessionId)) throw new IllegalArgumentException("Bạn không thuộc lớp này hoặc buổi học không còn mở");
        if (c.currentSessionId > 0 && c.currentSessionId != sessionId) leaveSession(c,false);
        SessionRecord s = db.getSession(sessionId).orElseThrow(() -> new IllegalArgumentException("Buổi học không tồn tại"));
        c.currentSessionId = sessionId; c.lastSeen = System.currentTimeMillis();
        UserRecord teacher = db.getUser(s.teacherId()).orElseThrow();
        send(c,"SESSION_JOINED|"+sessionId+"|"+s.classId()+"|"+Protocol.enc(s.className())+"|"+Protocol.enc(s.classCode())+"|"+Protocol.enc(s.teacherName())+"|"+Protocol.enc(teacher.email()));
        for (OnlineClient peer : onlineByEmail.values()) {
            if (peer == c || peer.currentSessionId != sessionId) continue;
            send(peer,"PEER_JOINED|"+Protocol.enc(c.email)+"|"+Protocol.enc(c.fullName)+"|"+c.role);
            send(c,"PEER_JOINED|"+Protocol.enc(peer.email)+"|"+Protocol.enc(peer.fullName)+"|"+peer.role);
        }
        broadcastSession(sessionId,"SYSTEM|"+Protocol.enc(c.fullName + " đã tham gia buổi học"));
        sendRoster(sessionId); log("JOIN SESSION " + sessionId + " <- " + c.email); refreshAllAsync();
    }

    private void leaveSession(OnlineClient c, boolean notify) {
        if (c == null || c.currentSessionId <= 0) return; long old = c.currentSessionId; c.currentSessionId = 0; c.screenSharing=false;
        broadcastSession(old,"PEER_LEFT|"+Protocol.enc(c.email));
        broadcastSession(old,"SYSTEM|"+Protocol.enc(c.fullName + " đã rời buổi học"));
        if (notify) send(c,"SESSION_LEFT|"+old);
        try { sendRoster(old); } catch (Exception e) { log("ROSTER ERROR: " + e.getMessage()); }
        refreshAllAsync();
    }

    private void chat(InetAddress a,int port,String[] p) {
        OnlineClient c = requireOnline(a,port); if (c.currentSessionId<=0) throw new IllegalArgumentException("Bạn chưa ở trong buổi học"); if (p.length<2) return;
        broadcastSession(c.currentSessionId,"CHAT|"+Protocol.enc(c.fullName)+"|"+p[1]);
    }
    private void raiseHand(InetAddress a,int port) {
        OnlineClient c=requireOnline(a,port); if(c.currentSessionId<=0) throw new IllegalArgumentException("Bạn chưa ở trong buổi học");
        broadcastSession(c.currentSessionId,"RAISE_HAND|"+Protocol.enc(c.fullName)+"|"+Protocol.enc(c.email));
    }
    private void mediaState(InetAddress a,int port,String[] p) throws Exception {
        OnlineClient c=requireOnline(a,port); if(p.length>=3){c.micOn="1".equals(p[1]);c.camOn="1".equals(p[2]);} if(c.currentSessionId>0) sendRoster(c.currentSessionId);
    }

    private void screenState(InetAddress a,int port,String[] p) throws Exception {
        OnlineClient c=requireOnline(a,port);if(c.currentSessionId<=0)throw new IllegalArgumentException("Bạn chưa ở trong buổi học");
        c.screenSharing=p.length>=2&&"1".equals(p[1]);
        broadcastSession(c.currentSessionId,"SCREEN_STATE|"+Protocol.enc(c.email)+"|"+Protocol.enc(c.fullName)+"|"+(c.screenSharing?1:0));
        sendRoster(c.currentSessionId);
        log("SCREEN "+(c.screenSharing?"START ":"STOP ")+c.email+" session="+c.currentSessionId);
    }

    private void fileStart(InetAddress a,int port,String[]p){
        OnlineClient c=requireOnline(a,port);if(c.currentSessionId<=0)throw new IllegalArgumentException("Bạn chưa ở trong buổi học");if(p.length<5)throw new IllegalArgumentException("FILE_START thiếu dữ liệu");
        String id=p[1],encodedName=p[2];long size=Long.parseLong(p[3]);int total=Integer.parseInt(p[4]);
        if(id.isBlank()||size<=0||size>MAX_FILE_SIZE||total<=0||total>10000)throw new IllegalArgumentException("Tệp không hợp lệ hoặc vượt quá 20 MB");
        FileTransfer f=new FileTransfer(c.email,c.fullName,c.currentSessionId,id,encodedName,size,total);fileTransfers.put(fileKey(c.email,id),f);
        broadcastSessionExcept(c.currentSessionId,c.email,"FILE_START|"+Protocol.enc(c.email)+"|"+Protocol.enc(c.fullName)+"|"+id+"|"+encodedName+"|"+size+"|"+total);
        send(c,"FILE_START_OK|"+id);log("FILE START "+c.email+" "+Protocol.dec(encodedName)+" size="+size+" session="+c.currentSessionId);
    }

    private void fileChunk(InetAddress a,int port,String[]p){
        OnlineClient c=requireOnline(a,port);if(p.length<4)throw new IllegalArgumentException("FILE_CHUNK thiếu dữ liệu");String id=p[1];int seq=Integer.parseInt(p[2]);FileTransfer f=fileTransfers.get(fileKey(c.email,id));
        if(f==null||f.sessionId!=c.currentSessionId)throw new IllegalArgumentException("Phiên gửi tệp không tồn tại");if(seq<0||seq>=f.totalChunks)throw new IllegalArgumentException("Số thứ tự chunk không hợp lệ");
        f.chunks.putIfAbsent(seq,p[3]);f.lastSeen=System.currentTimeMillis();broadcastSessionExcept(f.sessionId,c.email,"FILE_CHUNK|"+Protocol.enc(c.email)+"|"+id+"|"+seq+"|"+p[3]);send(c,"FILE_ACK|"+id+"|"+seq);
    }

    private void fileEnd(InetAddress a,int port,String[]p){
        OnlineClient c=requireOnline(a,port);if(p.length<2)return;String id=p[1];FileTransfer f=fileTransfers.get(fileKey(c.email,id));if(f==null)throw new IllegalArgumentException("Phiên gửi tệp không tồn tại");
        if(f.chunks.size()<f.totalChunks)throw new IllegalArgumentException("Server chưa nhận đủ chunk: "+f.chunks.size()+"/"+f.totalChunks);
        f.lastSeen=System.currentTimeMillis();String startMsg="FILE_START|"+Protocol.enc(f.senderEmail)+"|"+Protocol.enc(f.senderName)+"|"+id+"|"+f.encodedName+"|"+f.size+"|"+f.totalChunks;broadcastSessionExcept(f.sessionId,c.email,startMsg);String endMsg="FILE_END|"+Protocol.enc(c.email)+"|"+id;broadcastSessionExcept(f.sessionId,c.email,endMsg);broadcastSessionExcept(f.sessionId,c.email,endMsg);send(c,"FILE_SENT|"+id+"|"+f.encodedName);
    }

    private void fileMissing(InetAddress a,int port,String[]p){
        OnlineClient receiver=requireOnline(a,port);if(receiver.currentSessionId<=0||p.length<4)return;String sender=Protocol.dec(p[1]).toLowerCase(Locale.ROOT),id=p[2];FileTransfer f=fileTransfers.get(fileKey(sender,id));
        if(f==null||f.sessionId!=receiver.currentSessionId)return;for(String x:p[3].split(",")){if(x.isBlank())continue;try{int seq=Integer.parseInt(x);String data=f.chunks.get(seq);if(data!=null)send(receiver,"FILE_CHUNK|"+Protocol.enc(f.senderEmail)+"|"+id+"|"+seq+"|"+data);}catch(Exception ignored){}}
        send(receiver,"FILE_END|"+Protocol.enc(f.senderEmail)+"|"+id);f.lastSeen=System.currentTimeMillis();
    }

    private void fileReceived(InetAddress a,int port,String[]p){
        OnlineClient receiver=requireOnline(a,port);if(p.length<3)return;String sender=Protocol.dec(p[1]).toLowerCase(Locale.ROOT),id=p[2];FileTransfer f=fileTransfers.get(fileKey(sender,id));if(f==null||f.sessionId!=receiver.currentSessionId)return;f.receivedBy.add(receiver.email);f.lastSeen=System.currentTimeMillis();OnlineClient original=onlineByEmail.get(sender);if(original!=null&&original.currentSessionId==f.sessionId)send(original,"FILE_RECEIVED_BY|"+id+"|"+Protocol.enc(receiver.fullName));
    }

    private static String fileKey(String senderEmail,String id){return senderEmail.toLowerCase(Locale.ROOT)+"|"+id;}

    private void takeAttendance(InetAddress a,int port) throws Exception {
        OnlineClient teacher=requireOnline(a,port); if(!"TEACHER".equals(teacher.role)) throw new IllegalArgumentException("Chỉ GIÁO VIÊN mới được điểm danh"); if(teacher.currentSessionId<=0) throw new IllegalArgumentException("Bạn chưa ở trong buổi học");
        Set<Long> present = new HashSet<>();
        for(OnlineClient c:onlineByEmail.values()) if(c.currentSessionId==teacher.currentSessionId && "STUDENT".equals(c.role)) present.add(c.userId);
        AttendanceResult result=db.takeAttendance(teacher.currentSessionId,teacher.userId,present);
        List<String[]> rows=new ArrayList<>();
        for(AttendanceItem i:result.items()) rows.add(new String[]{String.valueOf(i.studentId()),i.fullName(),i.email(),i.status(),DatabaseManager.displayTime(i.recordedAt())});
        send(teacher,"ATTENDANCE_REPORT|"+result.attendanceId()+"|"+Protocol.enc(DatabaseManager.displayTime(result.takenAt()))+"|"+Protocol.packRows(rows));
        broadcastSession(teacher.currentSessionId,"ATTENDANCE_TAKEN|"+Protocol.enc(DatabaseManager.displayTime(result.takenAt())));
        log("ATTENDANCE session="+teacher.currentSessionId+" by "+teacher.email+" present="+present.size()+"/"+result.items().size());
    }

    private void relayRtc(InetAddress a,int port,String[] p,String cmd) {
        OnlineClient sender=requireOnline(a,port); if(sender.currentSessionId<=0) throw new IllegalArgumentException("Chưa ở trong buổi học"); if(p.length<3) throw new IllegalArgumentException("Signaling WebRTC thiếu dữ liệu");
        String targetEmail=Protocol.dec(p[1]).toLowerCase(Locale.ROOT); OnlineClient target=onlineByEmail.get(targetEmail);
        if(target==null || target.currentSessionId!=sender.currentSessionId) throw new IllegalArgumentException("Người nhận WebRTC không online trong cùng buổi học");
        if("RTC_ICE".equals(cmd)) {
            if(p.length<5) throw new IllegalArgumentException("ICE candidate thiếu dữ liệu");
            send(target,"RTC_ICE|"+Protocol.enc(sender.email)+"|"+p[2]+"|"+p[3]+"|"+p[4]);
        } else send(target,cmd+"|"+Protocol.enc(sender.email)+"|"+p[2]);
    }

    private void logout(InetAddress a,int port) {
        OnlineClient c=findOnline(a,port); if(c==null)return; leaveSession(c,false); onlineByEmail.remove(c.email,c); endpointToEmail.remove(endpointKey(a,port)); send(a,port,"LOGOUT_OK"); log("LOGOUT "+c.email); refreshAllAsync();
    }

    private void closeRuntimeSession(long sessionId, String reason) {
        for (OnlineClient member : new ArrayList<>(onlineByEmail.values())) {
            if (member.currentSessionId == sessionId) {
                send(member, "SESSION_CLOSED|" + sessionId + "|" + Protocol.enc(reason));
                member.currentSessionId = 0;
            }
        }
        fileTransfers.entrySet().removeIf(e->e.getValue().sessionId==sessionId);
        log("SESSION " + sessionId + " CLOSED | " + reason);
        refreshAllAsync();
    }


    private void sendRoster(long sessionId) throws Exception {
        SessionRecord s=db.getSession(sessionId).orElse(null); if(s==null)return; ClassRecord clazz=db.getClassById(s.classId()).orElse(null); if(clazz==null)return;
        List<String[]> rows=new ArrayList<>(); UserRecord teacher=db.getUser(clazz.teacherId()).orElse(null);
        if(teacher!=null){OnlineClient on=onlineByEmail.get(teacher.email()); boolean present=on!=null&&on.currentSessionId==sessionId; rows.add(new String[]{String.valueOf(teacher.id()),teacher.fullName(),teacher.email(),"TEACHER",present?"1":"0",present&&on.micOn?"1":"0",present&&on.camOn?"1":"0",present&&on.screenSharing?"1":"0"});}
        for(RosterStudent st:db.classRoster(clazz.id())){OnlineClient on=onlineByEmail.get(st.email().toLowerCase(Locale.ROOT));boolean present=on!=null&&on.currentSessionId==sessionId;rows.add(new String[]{String.valueOf(st.id()),st.fullName(),st.email(),"STUDENT",present?"1":"0",present&&on.micOn?"1":"0",present&&on.camOn?"1":"0",present&&on.screenSharing?"1":"0"});}
        rows.sort((x,y) -> DatabaseManager.viComparator().compare(x[1], y[1]));
        broadcastSession(sessionId,"ROSTER|"+Protocol.packRows(rows));
    }

    private void broadcastSession(long sessionId,String msg){for(OnlineClient c:new ArrayList<>(onlineByEmail.values()))if(c.currentSessionId==sessionId)send(c,msg);}
    private void broadcastSessionExcept(long sessionId,String exceptEmail,String msg){for(OnlineClient c:new ArrayList<>(onlineByEmail.values()))if(c.currentSessionId==sessionId&&!c.email.equalsIgnoreCase(exceptEmail))send(c,msg);}
    private void broadcastAll(String msg){for(OnlineClient c:new ArrayList<>(onlineByEmail.values()))send(c,msg);}
    private void send(OnlineClient c,String msg){if(c!=null)send(c.address,c.port,msg);}
    private void send(InetAddress address,int port,String msg){try{if(udpSocket==null||udpSocket.isClosed())return;byte[]d=msg.getBytes(StandardCharsets.UTF_8);udpSocket.send(new DatagramPacket(d,d.length,address,port));}catch(Exception e){log("UDP SEND ERROR: "+e.getMessage());}}
    private OnlineClient requireOnline(InetAddress a,int port){OnlineClient c=findOnline(a,port);if(c==null)throw new IllegalArgumentException("Client chưa xác thực UDP");c.lastSeen=System.currentTimeMillis();return c;}
    private OnlineClient findOnline(InetAddress a,int port){String email=endpointToEmail.get(endpointKey(a,port));return email==null?null:onlineByEmail.get(email);}
    private static String endpointKey(InetAddress a,int port){return a.getHostAddress()+":"+port;}

    private void removeOfflineClients(){
        if(!running)return;
        long now=System.currentTimeMillis();
        for(OnlineClient c:new ArrayList<>(onlineByEmail.values())) {
            if(now-c.lastSeen>OFFLINE_TIMEOUT_MS){
                long sid = c.currentSessionId;
                if (sid > 0 && "TEACHER".equals(c.role)) {
                    try { db.closeSession(c.userId, sid); } catch (Exception e) { log("AUTO CLOSE SESSION ERROR: " + e.getMessage()); }
                    closeRuntimeSession(sid, "Giáo viên mất kết nối");
                } else {
                    leaveSession(c,false);
                }
                onlineByEmail.remove(c.email,c);
                endpointToEmail.remove(endpointKey(c.address,c.port));
                log("OFFLINE "+c.email+" timed out");
            }
        }
        fileTransfers.entrySet().removeIf(e->now-e.getValue().lastSeen>FILE_TRANSFER_TIMEOUT_MS);
        refreshAllAsync();
    }

    private void approveSelected(String forcedRole){if(db==null)return;int row=pendingTable.getSelectedRow();if(row<0){msg("Chọn tài khoản cần duyệt.");return;}try{long id=Long.parseLong(String.valueOf(pendingModel.getValueAt(row,0)));String role=forcedRole==null?String.valueOf(pendingModel.getValueAt(row,4)):forcedRole;db.approveUser(id,role);log("APPROVE user="+id+" role="+role);refreshAll();}catch(Exception e){error(e);}}
    private void rejectSelected(){if(db==null)return;int row=pendingTable.getSelectedRow();if(row<0){msg("Chọn tài khoản cần từ chối.");return;}try{long id=Long.parseLong(String.valueOf(pendingModel.getValueAt(row,0)));db.rejectUser(id);log("REJECT user="+id);refreshAll();}catch(Exception e){error(e);}}
    private void setSelectedAccountStatus(String status){if(db==null)return;int row=accountTable.getSelectedRow();if(row<0){msg("Chọn tài khoản.");return;}try{long id=Long.parseLong(String.valueOf(accountModel.getValueAt(row,0)));db.setAccountStatus(id,status);log("ACCOUNT "+id+" -> "+status);refreshAll();}catch(Exception e){error(e);}}

    private void refreshAllAsync(){SwingUtilities.invokeLater(this::refreshAll);}
    private void refreshAll(){
        lblOnline.setText(String.valueOf(onlineByEmail.size()));
        onlineModel.setRowCount(0); List<OnlineClient> online=new ArrayList<>(onlineByEmail.values()); online.sort(Comparator.comparing(x->x.fullName,DatabaseManager.viComparator())); long now=System.currentTimeMillis();
        for(OnlineClient c:online)onlineModel.addRow(new Object[]{c.fullName,c.email,c.role,c.address.getHostAddress(),c.port,c.currentSessionId<=0?"-":c.currentSessionId,Math.max(0,(now-c.lastSeen)/1000)+" giây"});
        if(db==null)return;
        try{
            List<UserRecord> users=db.allUsers();lblUsers.setText(String.valueOf(users.size()));accountModel.setRowCount(0);for(UserRecord u:users)accountModel.addRow(new Object[]{u.id(),u.fullName(),u.email(),u.role()==null?"-":u.role(),u.status(),DatabaseManager.displayTime(u.lastLogin())});
            List<UserRecord> pending=db.pendingUsers();pendingModel.setRowCount(0);for(UserRecord u:pending)pendingModel.addRow(new Object[]{u.id(),u.fullName(),u.phone(),u.email(),u.requestedRole(),DatabaseManager.displayTime(u.createdAt())});
            List<ClassRecord> classes=db.allClasses();lblClasses.setText(String.valueOf(classes.size()));classModel.setRowCount(0);for(ClassRecord c:classes)classModel.addRow(new Object[]{c.id(),c.className(),c.classCode(),c.teacherName(),c.studentCount(),c.activeSessionId()==0?"-":c.activeSessionId()});
            List<SessionRecord> sessions=db.openSessions();lblSessions.setText(String.valueOf(sessions.size()));sessionModel.setRowCount(0);for(SessionRecord s:sessions){long count=onlineByEmail.values().stream().filter(x->x.currentSessionId==s.id()).count();sessionModel.addRow(new Object[]{s.id(),s.className(),s.classCode(),s.teacherName(),DatabaseManager.displayTime(s.startedAt()),count});}
        }catch(Exception e){log("REFRESH DB ERROR: "+e.getMessage());}
    }

    private void log(String s){String line="["+LocalDateTime.now().format(clock)+"] "+s;SwingUtilities.invokeLater(()->{logArea.append(line+"\n");logArea.setCaretPosition(logArea.getDocument().getLength());});}
    private void msg(String s){JOptionPane.showMessageDialog(this,s);}
    private void error(Exception e){JOptionPane.showMessageDialog(this,e.getMessage(),"Lỗi",JOptionPane.ERROR_MESSAGE);}

    private String findLanIp(){
        try{Enumeration<NetworkInterface> list=NetworkInterface.getNetworkInterfaces();String ethernet=null,fallback=null;while(list.hasMoreElements()){NetworkInterface ni=list.nextElement();if(!ni.isUp()||ni.isLoopback()||ni.isVirtual())continue;String n=(ni.getName()+" "+ni.getDisplayName()).toLowerCase(Locale.ROOT);if(n.contains("virtual")||n.contains("vmware")||n.contains("docker")||n.contains("radmin")||n.contains("host-only")||n.contains("wsl"))continue;Enumeration<InetAddress> addrs=ni.getInetAddresses();while(addrs.hasMoreElements()){InetAddress a=addrs.nextElement();if(!(a instanceof Inet4Address)||a.isLoopbackAddress()||a.isLinkLocalAddress()||!a.isSiteLocalAddress())continue;String ip=a.getHostAddress();if(n.contains("wi-fi")||n.contains("wifi")||n.contains("wlan")||n.contains("wireless"))return ip;if(n.contains("ethernet")&&ethernet==null)ethernet=ip;if(fallback==null)fallback=ip;}}if(ethernet!=null)return ethernet;if(fallback!=null)return fallback;}catch(Exception ignored){}return "127.0.0.1";
    }

    private static final class AuthSession {
        final String token; final long userId; final String fullName,email,role; volatile long lastSeen;
        AuthSession(String token,long userId,String fullName,String email,String role,long lastSeen){this.token=token;this.userId=userId;this.fullName=fullName;this.email=email.toLowerCase(Locale.ROOT);this.role=role;this.lastSeen=lastSeen;}
    }
    private static final class OnlineClient {
        final long userId; final String fullName,email,role; final InetAddress address; final int port; volatile long currentSessionId; volatile long lastSeen=System.currentTimeMillis(); volatile boolean micOn=true,camOn=true,screenSharing=false;
        OnlineClient(long userId,String fullName,String email,String role,InetAddress address,int port){this.userId=userId;this.fullName=fullName;this.email=email.toLowerCase(Locale.ROOT);this.role=role;this.address=address;this.port=port;}
    }
    private static final class FileTransfer {
        final String senderEmail,senderName;final long sessionId;final String id,encodedName;final long size;final int totalChunks;final Map<Integer,String>chunks=new ConcurrentHashMap<>();final Set<String>receivedBy=ConcurrentHashMap.newKeySet();volatile long lastSeen=System.currentTimeMillis();
        FileTransfer(String senderEmail,String senderName,long sessionId,String id,String encodedName,long size,int totalChunks){this.senderEmail=senderEmail.toLowerCase(Locale.ROOT);this.senderName=senderName;this.sessionId=sessionId;this.id=id;this.encodedName=encodedName;this.size=size;this.totalChunks=totalChunks;}
    }

    /** Page that follows the viewport width but keeps its natural height so the
     *  whole admin console can scroll vertically on 720p / scaled displays. */
    private static final class VerticalPage extends JPanel implements Scrollable {
        VerticalPage(LayoutManager layout){super(layout);}
        @Override public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect,int orientation,int direction){return 24;}
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect,int orientation,int direction){return Math.max(120,visibleRect.height-80);}
        @Override public boolean getScrollableTracksViewportWidth(){return true;}
        @Override public boolean getScrollableTracksViewportHeight(){return false;}
    }

}
