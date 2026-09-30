package client;

import common.Protocol;
import common.ModernUI;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

public class SmartClassroomClient extends JFrame {
    private static final int BUFFER_SIZE = 65507;
    private static final int FILE_CHUNK_SIZE = 8192;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private final Color NAVY = ModernUI.NAVY, BLUE = ModernUI.BLUE, BG = ModernUI.BG, RED = ModernUI.RED, GREEN = ModernUI.GREEN;

    private final CardLayout cardsLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardsLayout);

    // Connection screen.
    private final JTextField txtServerIp = new JTextField("127.0.0.1",18);
    private final JTextField txtTcpPort = new JTextField("5001",6);
    private final JTextField txtUdpPort = new JTextField("5000",6);
    private final JLabel lblConnectStatus = new JLabel("Nhập IP LAN của Server rồi kiểm tra kết nối.", SwingConstants.CENTER);

    // Login/register.
    private final JTextField txtEmail = new JTextField(24);
    private final JPasswordField txtPassword = new JPasswordField(24);
    private final JTextField forgotEmail = new JTextField(24);
    private final JTextField otpCode = new JTextField(12);
    private final JLabel lblOtpInfo = new JLabel("Nhập mã 6 số đã gửi tới email của bạn.", SwingConstants.CENTER);
    private final JPasswordField resetPassword = new JPasswordField(24);
    private final JPasswordField resetConfirm = new JPasswordField(24);
    private String resetEmail;
    private String resetToken;
    private final JTextField regFullName = new JTextField(24);
    private final JTextField regPhone = new JTextField(24);
    private final JTextField regEmail = new JTextField(24);
    private final JPasswordField regPassword = new JPasswordField(24);
    private final JPasswordField regConfirm = new JPasswordField(24);
    private final JComboBox<String> regRole = new JComboBox<>(new String[]{"HỌC SINH", "GIÁO VIÊN"});

    // Lobby.
    private final JLabel lblLobbyUser = new JLabel("-");
    private final DefaultTableModel myClassModel = model(new Object[]{"Tên lớp","Mã lớp","Giáo viên","Sinh viên","Buổi học"});
    private final JTable myClassTable = new JTable(myClassModel);
    private final DefaultTableModel availableModel = model(new Object[]{"Tên lớp","Mã lớp","Giáo viên","Sinh viên"});
    private final JTable availableTable = new JTable(availableModel);
    private final JButton btnCreateClass = new JButton("+ Tạo lớp");
    private final JButton btnOpenJoin = new JButton("Mở lớp học");
    private final JButton btnEnroll = new JButton("Đăng ký lớp đã chọn");
    private final JButton btnAttendanceHistory = new JButton("Lịch sử điểm danh");
    private final JButton btnRefresh = new JButton("Làm mới");

    // Meeting.
    private final JLabel lblMeetingTitle = new JLabel("-");
    private final JLabel lblMeetingStatus = new JLabel("Đang kết nối...");
    private final JPanel videoGrid = new JPanel(new GridLayout(0,2,8,8));
    private final CardLayout videoStageLayout = new CardLayout();
    private final JPanel videoStage = new JPanel(videoStageLayout);
    private final JPanel normalVideoPanel = new JPanel(new BorderLayout());
    private final JPanel shareModePanel = new JPanel(new BorderLayout(8,8));
    private final JPanel sharedScreenHost = new JPanel(new BorderLayout());
    private final JPanel thumbnailStrip = new JPanel(new FlowLayout(FlowLayout.LEFT,8,6));
    private final JScrollPane thumbnailScroll = new JScrollPane(thumbnailStrip, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    private final JLabel lblScreenShareTitle = new JLabel("Đang chia sẻ màn hình");
    private final JButton btnToggleThumbnails = new JButton("Ẩn camera nhỏ");
    private final DefaultTableModel rosterModel = model(new Object[]{"Họ và tên","Vai trò","Trạng thái","Mic","Cam","Màn hình"});
    private final JTable rosterTable = new JTable(rosterModel);
    private final JTextArea chatArea = new JTextArea();
    private final JTextField txtChat = new JTextField();
    private final JButton btnMic = new JButton("🎤 Mic: BẬT");
    private final JButton btnCam = new JButton("📷 Camera: BẬT");
    private final JButton btnRaise = new JButton("✋ Giơ tay");
    private final JButton btnAttendance = new JButton("✓ Điểm danh");
    private final JButton btnShareScreen = new JButton("🖥 Chia sẻ màn hình");
    private final JButton btnSendFile = new JButton("📎 Tệp");
    private final JButton btnLeave = new JButton("Rời buổi học");
    private final Map<String, VideoTile> videoTiles = new ConcurrentHashMap<>();
    private final Map<String, RosterRow> rosterByEmail = new ConcurrentHashMap<>();
    private final Map<String, IncomingFile> incomingFiles = new ConcurrentHashMap<>();
    private final Set<String> fileStartAcks = ConcurrentHashMap.newKeySet();
    private final Set<String> fileChunkAcks = ConcurrentHashMap.newKeySet();

    // State/network.
    private String serverHost = "127.0.0.1";
    private int tcpPort = 5001, udpPort = 5000;
    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private volatile boolean udpRunning;
    private Thread udpReceiver;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean heartbeatStarted;

    private String token, fullName, email, role;
    private long userId;
    private List<ClassRow> myClasses = new ArrayList<>();
    private List<ClassRow> availableClasses = new ArrayList<>();
    private long currentSessionId, currentClassId;
    private String currentClassName, currentClassCode, currentTeacherEmail;
    private boolean micOn = true, camOn = true, screenSharing;
    private String activeScreenSharer;
    private boolean thumbnailsVisible = true;
    private final Set<String> screenSharers = ConcurrentHashMap.newKeySet();
    private NativeWebRtcManager rtc;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            ModernUI.installGlobalDefaults();
            new SmartClassroomClient().setVisible(true);
        });
    }

    public SmartClassroomClient() {
        setTitle("Smart Classroom V3.11 - Modern Desktop");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1220,780);
        setMinimumSize(new Dimension(1080,700));
        setLocationRelativeTo(null);
        cards.add(buildConnectPanel(),"CONNECT");
        cards.add(buildLoginPanel(),"LOGIN");
        cards.add(buildRegisterPanel(),"REGISTER");
        cards.add(buildForgotPasswordPanel(),"FORGOT");
        cards.add(buildOtpPanel(),"OTP");
        cards.add(buildResetPasswordPanel(),"RESET_PASSWORD");
        cards.add(buildLobbyPanel(),"LOBBY");
        cards.add(buildMeetingPanel(),"MEETING");
        setContentPane(cards); cardsLayout.show(cards,"CONNECT");
        addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){shutdown();dispose();}});
    }

    private static DefaultTableModel model(Object[] cols) {
        return new DefaultTableModel(cols,0){@Override public boolean isCellEditable(int r,int c){return false;}};
    }

    private JPanel buildConnectPanel() {
        JPanel root=centerCard("KẾT NỐI SMART CLASSROOM","Kết nối tới máy chủ lớp học an toàn qua TCP + UDP.");
        JPanel card=(JPanel)root.getClientProperty("authCard");
        JPanel form=(JPanel)((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        addRow(form,"IP LAN Server",txtServerIp); addRow(form,"TCP Port",txtTcpPort); addRow(form,"UDP Port",txtUdpPort);
        form.add(Box.createVerticalStrut(18));
        JButton test=primary("KẾT NỐI SERVER"); form.add(test); form.add(Box.createVerticalStrut(14));
        lblConnectStatus.setForeground(ModernUI.MUTED);lblConnectStatus.setFont(new Font("Segoe UI",Font.PLAIN,12));lblConnectStatus.setAlignmentX(Component.LEFT_ALIGNMENT);form.add(lblConnectStatus);
        test.addActionListener(e->testServerConnection());
        return root;
    }

    private JPanel buildLoginPanel() {
        JPanel root=centerCard("CHÀO MỪNG TRỞ LẠI","Đăng nhập để tiếp tục vào lớp học trực tuyến của bạn.");
        JPanel card=(JPanel)root.getClientProperty("authCard");
        JPanel form=(JPanel)((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        addRow(form,"Email",txtEmail); addRow(form,"Mật khẩu",txtPassword);
        form.add(Box.createVerticalStrut(18));
        JButton login=primary("ĐĂNG NHẬP");
        JButton forgot=secondary("Quên mật khẩu?");
        JButton register=secondary("Chưa có tài khoản? Đăng ký");
        JButton back=ghost("← Đổi Server");
        form.add(login); form.add(Box.createVerticalStrut(8)); form.add(forgot); form.add(Box.createVerticalStrut(8)); form.add(register); form.add(Box.createVerticalStrut(8)); form.add(back);
        login.addActionListener(e->login());
        forgot.addActionListener(e->{forgotEmail.setText(txtEmail.getText().trim());cardsLayout.show(cards,"FORGOT");});
        register.addActionListener(e->cardsLayout.show(cards,"REGISTER"));
        back.addActionListener(e->cardsLayout.show(cards,"CONNECT"));
        return root;
    }

    private JPanel buildRegisterPanel() {
        JPanel root=centerCard("TẠO TÀI KHOẢN","Điền thông tin thật. Vai trò sẽ được Server Admin xác nhận trước khi sử dụng.");
        JPanel card=(JPanel)root.getClientProperty("authCard");
        card.setPreferredSize(new Dimension(620,790));
        JPanel form=(JPanel)((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        addRow(form,"Họ và tên",regFullName); addRow(form,"Số điện thoại",regPhone); addRow(form,"Email",regEmail); addRow(form,"Mật khẩu",regPassword); addRow(form,"Xác nhận mật khẩu",regConfirm); addRow(form,"Vai trò mong muốn",regRole);
        form.add(Box.createVerticalStrut(16));
        JButton submit=primary("TẠO TÀI KHOẢN"); JButton back=ghost("← Quay lại đăng nhập"); form.add(submit);form.add(Box.createVerticalStrut(8));form.add(back);
        submit.addActionListener(e->register());back.addActionListener(e->cardsLayout.show(cards,"LOGIN"));return root;
    }

    private JPanel buildForgotPasswordPanel() {
        JPanel root=centerCard("QUÊN MẬT KHẨU","Nhập email đã đăng ký. Mã OTP 6 số sẽ được gửi trực tiếp tới email này.");
        JPanel card=(JPanel)root.getClientProperty("authCard");
        JPanel form=(JPanel)((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        addRow(form,"Email tài khoản nhận OTP",forgotEmail);
        form.add(Box.createVerticalStrut(18));
        JButton send=primary("GỬI MÃ XÁC MINH");
        JButton back=ghost("← Quay lại đăng nhập");
        form.add(send);form.add(Box.createVerticalStrut(8));form.add(back);
        send.addActionListener(e->requestPasswordReset());back.addActionListener(e->cardsLayout.show(cards,"LOGIN"));return root;
    }

    private JPanel buildOtpPanel() {
        JPanel root=centerCard("XÁC MINH EMAIL","Nhập mã OTP 6 số vừa được gửi tới hộp thư của bạn.");
        JPanel card=(JPanel)root.getClientProperty("authCard");
        JPanel form=(JPanel)((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        lblOtpInfo.setForeground(ModernUI.MUTED);lblOtpInfo.setFont(new Font("Segoe UI",Font.PLAIN,12));lblOtpInfo.setAlignmentX(Component.LEFT_ALIGNMENT);form.add(lblOtpInfo);
        addRow(form,"Mã xác minh 6 số",otpCode);
        form.add(Box.createVerticalStrut(18));
        JButton verify=primary("XÁC MINH MÃ");
        JButton resend=secondary("Gửi lại mã");
        JButton cancel=ghost("← Hủy và về đăng nhập");
        form.add(verify);form.add(Box.createVerticalStrut(8));form.add(resend);form.add(Box.createVerticalStrut(8));form.add(cancel);
        verify.addActionListener(e->verifyResetOtp());resend.addActionListener(e->requestPasswordReset());cancel.addActionListener(e->{clearResetState();cardsLayout.show(cards,"LOGIN");});
        return root;
    }

    private JPanel buildResetPasswordPanel() {
        JPanel root=centerCard("ĐẶT MẬT KHẨU MỚI","Xác minh thành công. Hãy tạo mật khẩu mới cho tài khoản của bạn.");
        JPanel card=(JPanel)root.getClientProperty("authCard");
        JPanel form=(JPanel)((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        addRow(form,"Mật khẩu mới",resetPassword);
        addRow(form,"Xác nhận mật khẩu mới",resetConfirm);
        form.add(Box.createVerticalStrut(18));
        JButton save=primary("CẬP NHẬT MẬT KHẨU");
        JButton cancel=ghost("← Hủy và về đăng nhập");
        form.add(save); form.add(Box.createVerticalStrut(8)); form.add(cancel);
        save.addActionListener(e->submitNewPassword());
        cancel.addActionListener(e->{clearResetState();cardsLayout.show(cards,"LOGIN");});
        return root;
    }

    private JPanel centerCard(String title,String subtitle) {
        ModernUI.GradientPanel root=new ModernUI.GradientPanel(new Color(239,246,255),new Color(248,250,252),0);
        root.setLayout(new BorderLayout());

        ModernUI.CardPanel card=new ModernUI.CardPanel(24,Color.WHITE,ModernUI.BORDER,true);
        card.setLayout(new BorderLayout(0,22));
        card.setPreferredSize(new Dimension(620,620));
        card.setBorder(new EmptyBorder(34,48,34,48));
        root.putClientProperty("authCard",card);

        JPanel head=new JPanel();head.setOpaque(false);head.setLayout(new BoxLayout(head,BoxLayout.Y_AXIS));
        ModernUI.CardPanel logo=new ModernUI.CardPanel(18,ModernUI.BLUE,ModernUI.BLUE,false);logo.setLayout(new GridBagLayout());logo.setPreferredSize(new Dimension(54,54));logo.setMaximumSize(new Dimension(54,54));logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel logoText=new JLabel("SC");logoText.setForeground(Color.WHITE);logoText.setFont(new Font("Segoe UI",Font.BOLD,18));logo.add(logoText);
        JLabel h=new JLabel(title,SwingConstants.CENTER);h.setAlignmentX(Component.CENTER_ALIGNMENT);h.setFont(new Font("Segoe UI",Font.BOLD,27));h.setForeground(ModernUI.TEXT);
        JLabel s=new JLabel("<html><div style='text-align:center;width:430px'>"+subtitle+"</div></html>",SwingConstants.CENTER);s.setAlignmentX(Component.CENTER_ALIGNMENT);s.setForeground(ModernUI.MUTED);s.setFont(new Font("Segoe UI",Font.PLAIN,13));
        head.add(logo);head.add(Box.createVerticalStrut(16));head.add(h);head.add(Box.createVerticalStrut(8));head.add(s);card.add(head,BorderLayout.NORTH);

        JPanel form=new JPanel();form.setOpaque(false);form.setLayout(new BoxLayout(form,BoxLayout.Y_AXIS));card.add(form,BorderLayout.CENTER);

        // Auth pages can be taller than a laptop viewport (especially Register).
        // Put the card in a real scroll viewport instead of allowing Swing to clip
        // the controls at the bottom.  The card stays centered when there is space.
        JPanel stage=new JPanel(new GridBagLayout());
        stage.setOpaque(false);
        stage.setBorder(new EmptyBorder(28,24,28,24));
        stage.add(card);
        JScrollPane scroller=new JScrollPane(stage,JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setUnitIncrement(22);
        scroller.getVerticalScrollBar().setBlockIncrement(120);
        scroller.getVerticalScrollBar().setPreferredSize(new Dimension(10,0));
        root.add(scroller,BorderLayout.CENTER);
        return root;
    }
    private void addRow(JPanel form,String label,JComponent input){
        JLabel l=new JLabel(label);l.setAlignmentX(Component.LEFT_ALIGNMENT);l.setFont(new Font("Segoe UI",Font.BOLD,13));l.setForeground(ModernUI.TEXT);
        ModernUI.styleInput(input);input.setMaximumSize(new Dimension(Integer.MAX_VALUE,ModernUI.INPUT_HEIGHT));input.setMinimumSize(new Dimension(120,ModernUI.INPUT_HEIGHT));input.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(Box.createVerticalStrut(9));form.add(l);form.add(Box.createVerticalStrut(6));form.add(input);
    }
    private JButton primary(String text){JButton b=new JButton(text);ModernUI.styleButton(b,ModernUI.ButtonKind.PRIMARY);b.setMaximumSize(new Dimension(Integer.MAX_VALUE,44));b.setAlignmentX(Component.LEFT_ALIGNMENT);return b;}
    private JButton secondary(String text){JButton b=new JButton(text);ModernUI.styleButton(b,ModernUI.ButtonKind.SECONDARY);b.setMaximumSize(new Dimension(Integer.MAX_VALUE,42));b.setAlignmentX(Component.LEFT_ALIGNMENT);return b;}
    private JButton ghost(String text){JButton b=new JButton(text);ModernUI.styleButton(b,ModernUI.ButtonKind.GHOST);b.setMaximumSize(new Dimension(Integer.MAX_VALUE,40));b.setAlignmentX(Component.LEFT_ALIGNMENT);return b;}

    private JPanel buildLobbyPanel() {
        JPanel root=new JPanel(new BorderLayout(0,14));root.setBackground(ModernUI.BG);root.setBorder(new EmptyBorder(18,18,18,18));

        ModernUI.GradientPanel head=new ModernUI.GradientPanel(ModernUI.NAVY,new Color(30,64,175),22);head.setLayout(new BorderLayout(18,0));head.setBorder(new EmptyBorder(20,24,20,24));
        JPanel brand=new JPanel();brand.setOpaque(false);brand.setLayout(new BoxLayout(brand,BoxLayout.Y_AXIS));
        JLabel title=new JLabel("SMART CLASSROOM");title.setFont(new Font("Segoe UI",Font.BOLD,24));title.setForeground(Color.WHITE);
        JLabel sub=new JLabel("Không gian lớp học trực tuyến của bạn");sub.setFont(new Font("Segoe UI",Font.PLAIN,13));sub.setForeground(new Color(219,234,254));
        brand.add(title);brand.add(Box.createVerticalStrut(3));brand.add(sub);head.add(brand,BorderLayout.WEST);
        lblLobbyUser.setForeground(Color.WHITE);lblLobbyUser.setFont(new Font("Segoe UI",Font.BOLD,13));head.add(lblLobbyUser,BorderLayout.EAST);root.add(head,BorderLayout.NORTH);

        ModernUI.CardPanel content=new ModernUI.CardPanel();content.setLayout(new BorderLayout());content.setBorder(new EmptyBorder(10,12,12,12));
        JTabbedPane tabs=new JTabbedPane();ModernUI.styleTabs(tabs);
        JPanel mine=new JPanel(new BorderLayout(10,10));mine.setOpaque(false);ModernUI.styleTable(myClassTable);mine.add(ModernUI.scroll(myClassTable));
        JPanel acts=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,6));acts.setOpaque(false);
        ModernUI.styleButton(btnRefresh,ModernUI.ButtonKind.SECONDARY);ModernUI.styleButton(btnAttendanceHistory,ModernUI.ButtonKind.SECONDARY);ModernUI.styleButton(btnCreateClass,ModernUI.ButtonKind.PRIMARY);ModernUI.styleButton(btnOpenJoin,ModernUI.ButtonKind.SUCCESS);
        acts.add(btnRefresh);acts.add(btnAttendanceHistory);acts.add(btnCreateClass);acts.add(btnOpenJoin);mine.add(acts,BorderLayout.SOUTH);tabs.addTab("Lớp học của tôi",mine);
        JPanel available=new JPanel(new BorderLayout(10,10));available.setOpaque(false);ModernUI.styleTable(availableTable);available.add(ModernUI.scroll(availableTable));JPanel a2=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,6));a2.setOpaque(false);ModernUI.styleButton(btnEnroll,ModernUI.ButtonKind.PRIMARY);a2.add(btnEnroll);available.add(a2,BorderLayout.SOUTH);tabs.addTab("Khám phá & đăng ký lớp",available);content.add(tabs);root.add(content,BorderLayout.CENTER);

        JButton logout=new JButton("Đăng xuất");ModernUI.styleButton(logout,ModernUI.ButtonKind.GHOST);logout.addActionListener(e->logout());JPanel south=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));south.setOpaque(false);south.add(logout);root.add(south,BorderLayout.SOUTH);
        btnRefresh.addActionListener(e->refreshLobby());btnCreateClass.addActionListener(e->createClass());btnOpenJoin.addActionListener(e->openOrJoinSelected());btnEnroll.addActionListener(e->enrollSelected());btnAttendanceHistory.addActionListener(e->showAttendanceHistory());
        return root;
    }

    private JPanel buildMeetingPanel() {
        JPanel root=new JPanel(new BorderLayout(0,10));root.setBackground(new Color(11,15,23));root.setBorder(new EmptyBorder(10,12,12,12));

        ModernUI.GradientPanel head=new ModernUI.GradientPanel(new Color(17,24,39),new Color(30,41,59),18);head.setLayout(new BorderLayout(14,0));head.setBorder(new EmptyBorder(12,18,12,18));
        lblMeetingTitle.setForeground(Color.WHITE);lblMeetingTitle.setFont(new Font("Segoe UI",Font.BOLD,18));lblMeetingStatus.setForeground(new Color(134,239,172));lblMeetingStatus.setFont(new Font("Segoe UI",Font.BOLD,12));head.add(lblMeetingTitle,BorderLayout.WEST);head.add(lblMeetingStatus,BorderLayout.EAST);root.add(head,BorderLayout.NORTH);

        videoGrid.setBackground(new Color(11,15,23));videoGrid.setBorder(new EmptyBorder(4,4,4,4));
        JScrollPane videos=new JScrollPane(videoGrid);videos.setBorder(null);videos.getViewport().setBackground(new Color(11,15,23));videos.getVerticalScrollBar().setUnitIncrement(18);
        normalVideoPanel.setBackground(new Color(11,15,23));normalVideoPanel.add(videos,BorderLayout.CENTER);

        shareModePanel.setBackground(new Color(11,15,23));
        sharedScreenHost.setBackground(Color.BLACK);sharedScreenHost.setBorder(new ModernUI.RoundedBorder(new Color(59,130,246),16,2));
        ModernUI.CardPanel shareBar=new ModernUI.CardPanel(14,new Color(31,41,55),new Color(55,65,81),false);shareBar.setLayout(new BorderLayout(8,0));shareBar.setBorder(new EmptyBorder(8,12,8,10));
        lblScreenShareTitle.setForeground(Color.WHITE);lblScreenShareTitle.setFont(new Font("Segoe UI",Font.BOLD,13));ModernUI.styleCompactButton(btnToggleThumbnails,ModernUI.ButtonKind.DARK);
        btnToggleThumbnails.addActionListener(e->{thumbnailsVisible=!thumbnailsVisible;thumbnailScroll.setVisible(thumbnailsVisible);btnToggleThumbnails.setText(thumbnailsVisible?"Ẩn camera nhỏ":"Hiện camera nhỏ");shareModePanel.revalidate();shareModePanel.repaint();});
        shareBar.add(lblScreenShareTitle,BorderLayout.WEST);shareBar.add(btnToggleThumbnails,BorderLayout.EAST);
        thumbnailStrip.setBackground(new Color(11,15,23));thumbnailScroll.setBorder(BorderFactory.createEmptyBorder());thumbnailScroll.getViewport().setBackground(new Color(11,15,23));thumbnailScroll.setPreferredSize(new Dimension(100,150));
        shareModePanel.add(shareBar,BorderLayout.NORTH);shareModePanel.add(sharedScreenHost,BorderLayout.CENTER);shareModePanel.add(thumbnailScroll,BorderLayout.SOUTH);
        videoStage.setBackground(new Color(11,15,23));videoStage.add(normalVideoPanel,"GRID");videoStage.add(shareModePanel,"SHARE");videoStageLayout.show(videoStage,"GRID");

        ModernUI.styleTable(rosterTable);chatArea.setEditable(false);chatArea.setLineWrap(true);chatArea.setWrapStyleWord(true);chatArea.setFont(new Font("Segoe UI",Font.PLAIN,13));chatArea.setBackground(new Color(248,250,252));chatArea.setForeground(ModernUI.TEXT);chatArea.setBorder(new EmptyBorder(12,12,12,12));
        JTabbedPane side=new JTabbedPane();ModernUI.styleTabs(side);side.setBackground(Color.WHITE);side.addTab("Thành viên",ModernUI.scroll(rosterTable));
        JPanel chat=new JPanel(new BorderLayout(8,8));chat.setBackground(Color.WHITE);chat.setBorder(new EmptyBorder(8,8,8,8));chat.add(ModernUI.scroll(chatArea),BorderLayout.CENTER);
        JButton send=primary("Gửi");send.setPreferredSize(new Dimension(74,40));send.setMaximumSize(new Dimension(74,40));ModernUI.styleInput(txtChat);ModernUI.styleCompactButton(btnSendFile,ModernUI.ButtonKind.SECONDARY);
        ModernUI.CardPanel composer=new ModernUI.CardPanel(16,Color.WHITE,ModernUI.BORDER,false);composer.setLayout(new BorderLayout(6,0));composer.setBorder(new EmptyBorder(4,4,4,4));composer.add(txtChat,BorderLayout.CENTER);JPanel chatActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));chatActions.setOpaque(false);chatActions.add(btnSendFile);chatActions.add(send);composer.add(chatActions,BorderLayout.EAST);chat.add(composer,BorderLayout.SOUTH);side.addTab("Trò chuyện",chat);side.setPreferredSize(new Dimension(350,500));
        ModernUI.CardPanel sideCard=new ModernUI.CardPanel(18,Color.WHITE,ModernUI.BORDER,true);sideCard.setLayout(new BorderLayout());sideCard.setBorder(new EmptyBorder(6,6,6,6));sideCard.add(side);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,videoStage,sideCard);split.setResizeWeight(.76);split.setDividerLocation(830);split.setDividerSize(8);split.setBorder(null);split.setBackground(new Color(11,15,23));root.add(split,BorderLayout.CENTER);

        ModernUI.CardPanel controls=new ModernUI.CardPanel(20,new Color(17,24,39),new Color(55,65,81),false);controls.setLayout(new FlowLayout(FlowLayout.CENTER,8,7));controls.setBorder(new EmptyBorder(2,8,2,8));
        ModernUI.styleCompactButton(btnMic,ModernUI.ButtonKind.DARK);ModernUI.styleCompactButton(btnCam,ModernUI.ButtonKind.DARK);ModernUI.styleCompactButton(btnShareScreen,ModernUI.ButtonKind.PRIMARY);ModernUI.styleCompactButton(btnRaise,ModernUI.ButtonKind.DARK);ModernUI.styleCompactButton(btnAttendance,ModernUI.ButtonKind.SUCCESS);ModernUI.styleCompactButton(btnLeave,ModernUI.ButtonKind.DANGER);
        for(JButton b:List.of(btnMic,btnCam,btnShareScreen,btnRaise,btnAttendance,btnLeave)){b.setFont(new Font("Segoe UI",Font.BOLD,12));controls.add(b);}root.add(controls,BorderLayout.SOUTH);
        send.addActionListener(e->sendChat());txtChat.addActionListener(e->sendChat());btnSendFile.addActionListener(e->chooseAndSendFile());btnMic.addActionListener(e->toggleMic());btnCam.addActionListener(e->toggleCam());btnShareScreen.addActionListener(e->toggleScreenShare());btnRaise.addActionListener(e->sendUdp("RAISE_HAND"));btnAttendance.addActionListener(e->sendUdp("ATTENDANCE_START"));btnLeave.addActionListener(e->leaveMeeting());
        return root;
    }

    private void testServerConnection() {
        try { serverHost=txtServerIp.getText().trim();tcpPort=Integer.parseInt(txtTcpPort.getText().trim());udpPort=Integer.parseInt(txtUdpPort.getText().trim()); }
        catch(Exception e){error("IP/Port không hợp lệ.");return;}
        lblConnectStatus.setText("Đang kiểm tra...");
        CompletableFuture.runAsync(()->{
            try{String r=tcpRequest("PING");SwingUtilities.invokeLater(()->{if("PONG".equals(r)){lblConnectStatus.setText("Đã kết nối Server "+serverHost+":"+tcpPort);cardsLayout.show(cards,"LOGIN");}else lblConnectStatus.setText("Server phản hồi không hợp lệ.");});}
            catch(Exception e){SwingUtilities.invokeLater(()->lblConnectStatus.setText("Không kết nối được: "+e.getMessage()));}
        });
    }

    private void register() {
        String pass=new String(regPassword.getPassword()),confirm=new String(regConfirm.getPassword());if(!pass.equals(confirm)){error("Mật khẩu xác nhận không khớp.");return;}
        String requested="GIÁO VIÊN".equals(regRole.getSelectedItem())?"TEACHER":"STUDENT";
        String req="REGISTER|"+Protocol.enc(regFullName.getText())+"|"+Protocol.enc(regPhone.getText())+"|"+Protocol.enc(regEmail.getText())+"|"+Protocol.enc(pass)+"|"+requested;
        asyncRequest(req,r->{String[]p=r.split("\\|",-1);if("REGISTER_OK".equals(p[0])){info(Protocol.dec(p[1]));txtEmail.setText(regEmail.getText().trim());cardsLayout.show(cards,"LOGIN");}else error(responseError(r));});
    }

    private void login() {
        String req="LOGIN|"+Protocol.enc(txtEmail.getText())+"|"+Protocol.enc(new String(txtPassword.getPassword()));
        asyncRequest(req,r->{String[]p=r.split("\\|",-1);if("LOGIN_OK".equals(p[0])&&p.length>=6){token=p[1];userId=Long.parseLong(p[2]);fullName=Protocol.dec(p[3]);role=p[4];email=Protocol.dec(p[5]).toLowerCase(Locale.ROOT);connectUdp();}else if("LOGIN_FAIL".equals(p[0])){String code=p.length>1?p[1]:"";String msg=p.length>2?Protocol.dec(p[2]):"Đăng nhập thất bại";if("NOT_FOUND".equals(code)){int x=JOptionPane.showConfirmDialog(this,msg+"\nMở form đăng ký?","Chưa có tài khoản",JOptionPane.YES_NO_OPTION);if(x==JOptionPane.YES_OPTION){regEmail.setText(txtEmail.getText().trim());cardsLayout.show(cards,"REGISTER");}}else error(msg);}else error(responseError(r));});
    }

    private void requestPasswordReset() {
        String mail=forgotEmail.getText().trim().toLowerCase(Locale.ROOT);
        if(!mail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")){error("Email không hợp lệ.");return;}
        asyncRequest("FORGOT_PASSWORD|"+Protocol.enc(mail),r->{
            String[]p=r.split("\\|",-1);
            if("FORGOT_OK".equals(p[0])){
                resetEmail=mail; resetToken=null; otpCode.setText("");
                String msg=p.length>1?Protocol.dec(p[1]):"Đã gửi OTP tới email tài khoản của bạn.";
                lblOtpInfo.setText("<html><div style='text-align:center'>"+msg+"</div></html>");
                cardsLayout.show(cards,"OTP");
            }else error(responseError(r));
        });
    }

    private void verifyResetOtp() {
        if(resetEmail==null||resetEmail.isBlank()){cardsLayout.show(cards,"FORGOT");return;}
        String code=otpCode.getText().trim();
        if(!code.matches("\\d{6}")){error("Mã xác minh phải gồm đúng 6 chữ số.");return;}
        asyncRequest("VERIFY_RESET_OTP|"+Protocol.enc(resetEmail)+"|"+Protocol.enc(code),r->{
            String[]p=r.split("\\|",-1);
            if("OTP_OK".equals(p[0])&&p.length>=2){
                resetToken=p[1]; resetPassword.setText(""); resetConfirm.setText("");
                cardsLayout.show(cards,"RESET_PASSWORD");
            }else if("OTP_FAIL".equals(p[0])){
                error(p.length>2?Protocol.dec(p[2]):"Mã xác minh không đúng.");
            }else error(responseError(r));
        });
    }

    private void submitNewPassword() {
        if(resetToken==null||resetToken.isBlank()){error("Phiên đặt lại mật khẩu không hợp lệ. Vui lòng xác minh email lại.");cardsLayout.show(cards,"FORGOT");return;}
        String pass=new String(resetPassword.getPassword());
        String confirm=new String(resetConfirm.getPassword());
        if(pass.length()<6){error("Mật khẩu mới phải có ít nhất 6 ký tự.");return;}
        if(!pass.equals(confirm)){error("Mật khẩu xác nhận không khớp.");return;}
        asyncRequest("RESET_PASSWORD|"+resetToken+"|"+Protocol.enc(pass),r->{
            String[]p=r.split("\\|",-1);
            if("RESET_OK".equals(p[0])){
                String msg=p.length>1?Protocol.dec(p[1]):"Đổi mật khẩu thành công.";
                String mail=resetEmail;
                clearResetState();
                txtEmail.setText(mail==null?"":mail); txtPassword.setText("");
                info(msg); cardsLayout.show(cards,"LOGIN");
            }else error(responseError(r));
        });
    }

    private void clearResetState(){resetEmail=null;resetToken=null;otpCode.setText("");resetPassword.setText("");resetConfirm.setText("");}

    private void connectUdp() {
        try {
            serverAddress=InetAddress.getByName(serverHost);udpSocket=new DatagramSocket();udpRunning=true;udpReceiver=new Thread(this::udpReceiveLoop,"udp-client-receiver");udpReceiver.setDaemon(true);udpReceiver.start();sendUdp("UDP_AUTH|"+token);
            if(!heartbeatStarted){heartbeatStarted=true;heartbeat.scheduleAtFixedRate(()->{if(udpRunning)sendUdp("HEARTBEAT");},2,3,TimeUnit.SECONDS);}
        } catch(Exception e){error("Không tạo được UDP Client: "+e.getMessage());}
    }

    private void udpReceiveLoop() {
        while(udpRunning){try{byte[]buf=new byte[BUFFER_SIZE];DatagramPacket packet=new DatagramPacket(buf,buf.length);udpSocket.receive(packet);String msg=new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8);SwingUtilities.invokeLater(()->processUdp(msg));}catch(SocketException e){break;}catch(Exception e){if(udpRunning)SwingUtilities.invokeLater(()->appendChat("[MẠNG] "+e.getMessage()));}}
    }

    private void processUdp(String msg) {
        String[]p=msg.split("\\|",-1);String cmd=p[0];
        try{
            switch(cmd){
                case "UDP_AUTH_OK"->{lblLobbyUser.setText(fullName+" | "+displayRole(role)+" | "+email);btnCreateClass.setVisible("TEACHER".equals(role));btnAttendanceHistory.setVisible("TEACHER".equals(role));btnEnroll.setVisible("STUDENT".equals(role));btnOpenJoin.setText("TEACHER".equals(role)?"Mở lớp học":"Tham gia buổi học");cardsLayout.show(cards,"LOBBY");refreshLobby();}
                case "SESSION_JOINED"->{currentSessionId=Long.parseLong(p[1]);currentClassId=Long.parseLong(p[2]);currentClassName=Protocol.dec(p[3]);currentClassCode=Protocol.dec(p[4]);currentTeacherEmail=Protocol.dec(p[6]).toLowerCase(Locale.ROOT);enterMeetingUi();}
                case "SESSION_LEFT"->{closeRtc();clearMeeting();cardsLayout.show(cards,"LOBBY");refreshLobby();}
                case "SESSION_CLOSED"->{String reason=p.length>2?Protocol.dec(p[2]):"Buổi học đã kết thúc";closeRtc();clearMeeting();cardsLayout.show(cards,"LOBBY");info(reason);refreshLobby();}
                case "PEER_JOINED"->{String peer=Protocol.dec(p[1]).toLowerCase(Locale.ROOT);String name=Protocol.dec(p[2]);String peerRole=p[3];ensureVideoTile(peer,name);if(rtc!=null&&rtc.isInitialized()&&"TEACHER".equals(role)&&"STUDENT".equals(peerRole))rtc.ensurePeer(peer,true);}
                case "PEER_LEFT"->{String peer=Protocol.dec(p[1]).toLowerCase(Locale.ROOT);if(rtc!=null)rtc.removePeer(peer);removeVideoTile(peer);}
                case "ROSTER"->updateRoster(Protocol.unpackRows(p[1]));
                case "CHAT"->appendChat("["+Protocol.dec(p[1])+"] "+Protocol.dec(p[2]));
                case "SYSTEM"->appendChat("[HỆ THỐNG] "+Protocol.dec(p[1]));
                case "SCREEN_STATE"->handleScreenState(p);
                case "FILE_START_OK"->{if(p.length>=2)fileStartAcks.add(p[1]);}
                case "FILE_ACK"->{if(p.length>=3)fileChunkAcks.add(p[1]+":"+p[2]);}
                case "FILE_SENT"->{if(p.length>=3)appendChat("[TỆP] Đã gửi " + Protocol.dec(p[2]) + " lên phòng học.");}
                case "FILE_RECEIVED_BY"->{if(p.length>=3)appendChat("[TỆP] " + Protocol.dec(p[2]) + " đã nhận tệp của bạn.");}
                case "FILE_START"->handleIncomingFileStart(p);
                case "FILE_CHUNK"->handleIncomingFileChunk(p);
                case "FILE_END"->handleIncomingFileEnd(p);
                case "RAISE_HAND"->appendChat("[GIƠ TAY] "+Protocol.dec(p[1])+" đang xin phát biểu.");
                case "ATTENDANCE_TAKEN"->appendChat("[ĐIỂM DANH] Giáo viên đã chốt điểm danh lúc "+Protocol.dec(p[1])+".");
                case "ATTENDANCE_REPORT"->showAttendanceReport(p);
                case "RTC_OFFER"->{if(rtc!=null)rtc.handleOffer(Protocol.dec(p[1]),Protocol.dec(p[2]));}
                case "RTC_ANSWER"->{if(rtc!=null)rtc.handleAnswer(Protocol.dec(p[1]),Protocol.dec(p[2]));}
                case "RTC_ICE"->{if(rtc!=null)rtc.handleIce(Protocol.dec(p[1]),Protocol.dec(p[2]),Integer.parseInt(p[3]),Protocol.dec(p[4]));}
                case "ERROR"->error(Protocol.dec(p[1]));
                case "SERVER_SHUTDOWN"->{error("Server đã dừng: "+Protocol.dec(p[1]));disconnectLocal();cardsLayout.show(cards,"CONNECT");}
                case "LOGOUT_OK"->{ }
                default->appendChat("[RAW] "+msg);
            }
        }catch(Exception e){appendChat("[CLIENT ERROR] "+e.getMessage());}
    }

    private void refreshLobby() {
        if(token==null)return;asyncRequest("LIST_MY_CLASSES|"+token,r->{if(r.startsWith("CLASSES|")){myClasses=parseClasses(r.substring("CLASSES|".length()));renderMyClasses();}else error(responseError(r));});
        if("STUDENT".equals(role))asyncRequest("LIST_AVAILABLE_CLASSES|"+token,r->{if(r.startsWith("CLASSES|")){availableClasses=parseClasses(r.substring("CLASSES|".length()));renderAvailable();}});
        else {availableClasses=List.of();renderAvailable();}
    }
    private List<ClassRow> parseClasses(String encoded){List<ClassRow>list=new ArrayList<>();for(String[]r:Protocol.unpackRows(encoded)){if(r.length>=7)list.add(new ClassRow(Long.parseLong(r[0]),r[1],r[2],r[3],Integer.parseInt(r[4]),Long.parseLong(r[5]),r[6]));}return list;}
    private void renderMyClasses(){myClassModel.setRowCount(0);for(ClassRow c:myClasses)myClassModel.addRow(new Object[]{c.name,c.code,c.teacher,c.students,c.activeSessionId>0?"ĐANG MỞ #"+c.activeSessionId:"Chưa mở"});}
    private void renderAvailable(){availableModel.setRowCount(0);for(ClassRow c:availableClasses)availableModel.addRow(new Object[]{c.name,c.code,c.teacher,c.students});}

    private void createClass() {
        if(!"TEACHER".equals(role))return;JTextField name=new JTextField(),code=new JTextField();JPanel p=new JPanel(new GridLayout(0,2,6,6));p.add(new JLabel("Tên lớp"));p.add(name);p.add(new JLabel("Mã số lớp"));p.add(code);if(JOptionPane.showConfirmDialog(this,p,"Tạo lớp học",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        asyncRequest("CREATE_CLASS|"+token+"|"+Protocol.enc(name.getText())+"|"+Protocol.enc(code.getText()),r->{if(r.startsWith("CLASS_CREATED|")){info("Đã tạo lớp "+Protocol.dec(r.split("\\|",-1)[2])+". Sinh viên có thể đăng ký lớp này.");refreshLobby();}else error(responseError(r));});
    }

    private void enrollSelected() {
        int row=availableTable.getSelectedRow();if(row<0||row>=availableClasses.size()){info("Chọn một lớp để đăng ký.");return;}ClassRow c=availableClasses.get(row);asyncRequest("ENROLL_CLASS|"+token+"|"+c.id,r->{if("ENROLL_OK".equals(r)){info("Đăng ký lớp thành công.");refreshLobby();}else error(responseError(r));});
    }

    private void openOrJoinSelected() {
        int row=myClassTable.getSelectedRow();if(row<0||row>=myClasses.size()){info("Chọn một lớp trước.");return;}ClassRow c=myClasses.get(row);
        if("TEACHER".equals(role)){
            asyncRequest("OPEN_SESSION|"+token+"|"+c.id,r->{String[]p=r.split("\\|",-1);if("SESSION_OPENED".equals(p[0]))sendUdp("JOIN_SESSION|"+p[1]);else error(responseError(r));});
        }else{
            if(c.activeSessionId<=0){info("Giáo viên chưa mở buổi học cho lớp này.");return;}sendUdp("JOIN_SESSION|"+c.activeSessionId);
        }
    }

    private void enterMeetingUi() {
        cardsLayout.show(cards,"MEETING");lblMeetingTitle.setText(currentClassName+"  |  "+currentClassCode+"  |  Session #"+currentSessionId);lblMeetingStatus.setText("Đã vào phòng - đang khởi động Camera/Mic");chatArea.setText("");micOn=true;camOn=true;screenSharing=false;activeScreenSharer=null;screenSharers.clear();thumbnailsVisible=true;thumbnailScroll.setVisible(true);btnToggleThumbnails.setText("Ẩn camera nhỏ");btnMic.setText("🎤 Mic: BẬT");btnCam.setText("📷 Camera: BẬT");btnShareScreen.setText("🖥 Chia sẻ màn hình");btnAttendance.setVisible("TEACHER".equals(role));
        videoGrid.removeAll();thumbnailStrip.removeAll();sharedScreenHost.removeAll();videoTiles.clear();VideoTile local=new VideoTile(fullName+" (Bạn)");local.setState("Đang mở camera...");videoTiles.put(NativeWebRtcManager.LOCAL_PEER,local);videoGrid.add(local);applyVideoLayout();
        rtc=new NativeWebRtcManager();boolean ok=rtc.initialize((peer,img)->SwingUtilities.invokeLater(()->{VideoTile t=videoTiles.get(peer);if(t==null&&peer!=null&&!NativeWebRtcManager.LOCAL_PEER.equals(peer)){RosterRow rr=rosterByEmail.get(peer);t=ensureVideoTile(peer,rr==null?peer:rr.name);}if(t!=null)t.setImage(img);}),new NativeWebRtcManager.SignalCallback(){public void sendOffer(String target,String sdp){sendUdp("RTC_OFFER|"+Protocol.enc(target)+"|"+Protocol.enc(sdp));}public void sendAnswer(String target,String sdp){sendUdp("RTC_ANSWER|"+Protocol.enc(target)+"|"+Protocol.enc(sdp));}public void sendIce(String target,String mid,int index,String candidate){sendUdp("RTC_ICE|"+Protocol.enc(target)+"|"+Protocol.enc(mid)+"|"+index+"|"+Protocol.enc(candidate));}},m->SwingUtilities.invokeLater(()->{lblMeetingStatus.setText(m);appendChat("[WEBRTC] "+m);}));
        if(!ok)local.setState("Thiếu thư viện WebRTC Native - chạy SETUP_LIBS.bat");sendUdp("MEDIA_STATE|1|1");
    }

    private VideoTile ensureVideoTile(String peer,String name){peer=peer.toLowerCase(Locale.ROOT);VideoTile existing=videoTiles.get(peer);if(existing!=null){existing.setDisplayName(name);return existing;}VideoTile t=new VideoTile(name);videoTiles.put(peer,t);applyVideoLayout();return t;}
    private void removeVideoTile(String peer){peer=peer.toLowerCase(Locale.ROOT);screenSharers.remove(peer);VideoTile t=videoTiles.remove(peer);if(t!=null&&t.getParent()!=null)t.getParent().remove(t);if(peer.equals(activeScreenSharer))activeScreenSharer=chooseScreenSharer();applyVideoLayout();}

    /**
     * Google-Meet-like focus layout. Khi có người share màn hình, tile của người đó được
     * chuyển sang vùng CENTER lớn. Các camera khác vẫn tồn tại và tiếp tục nhận frame,
     * chỉ được đưa xuống dải thumbnail hoặc tạm ẩn bằng nút "Ẩn camera nhỏ".
     */
    private void applyVideoLayout(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::applyVideoLayout);return;}
        String sharer=activeScreenSharer;
        if(sharer==null||!isActuallySharing(sharer)||!videoTiles.containsKey(sharer)){sharer=chooseScreenSharer();activeScreenSharer=sharer;}

        videoGrid.removeAll();thumbnailStrip.removeAll();sharedScreenHost.removeAll();
        List<String> keys=orderedVideoKeys();
        if(sharer==null){
            for(String key:keys){VideoTile tile=videoTiles.get(key);if(tile==null)continue;tile.setPreferredSize(new Dimension(320,220));tile.setMinimumSize(new Dimension(220,150));tile.setMaximumSize(new Dimension(Integer.MAX_VALUE,Integer.MAX_VALUE));videoGrid.add(tile);}
            videoStageLayout.show(videoStage,"GRID");
        }else{
            VideoTile focus=videoTiles.get(sharer);
            if(focus!=null){focus.setPreferredSize(new Dimension(900,560));focus.setMinimumSize(new Dimension(400,260));focus.setMaximumSize(new Dimension(Integer.MAX_VALUE,Integer.MAX_VALUE));sharedScreenHost.add(focus,BorderLayout.CENTER);}
            lblScreenShareTitle.setText(screenShareDisplayName(sharer)+" đang chia sẻ màn hình");
            for(String key:keys){if(key.equals(sharer))continue;VideoTile tile=videoTiles.get(key);if(tile==null)continue;tile.setPreferredSize(new Dimension(190,125));tile.setMinimumSize(new Dimension(170,110));tile.setMaximumSize(new Dimension(210,140));thumbnailStrip.add(tile);}
            thumbnailScroll.setVisible(thumbnailsVisible&&thumbnailStrip.getComponentCount()>0);
            videoStageLayout.show(videoStage,"SHARE");
        }
        videoGrid.revalidate();videoGrid.repaint();thumbnailStrip.revalidate();thumbnailStrip.repaint();sharedScreenHost.revalidate();sharedScreenHost.repaint();videoStage.revalidate();videoStage.repaint();
    }

    private List<String> orderedVideoKeys(){
        List<String> keys=new ArrayList<>(videoTiles.keySet());
        keys.sort((a,b)->{
            if(NativeWebRtcManager.LOCAL_PEER.equals(a))return NativeWebRtcManager.LOCAL_PEER.equals(b)?0:-1;
            if(NativeWebRtcManager.LOCAL_PEER.equals(b))return 1;
            String an=screenShareDisplayName(a),bn=screenShareDisplayName(b);
            return java.text.Collator.getInstance(new Locale("vi","VN")).compare(an,bn);
        });
        return keys;
    }
    private boolean isActuallySharing(String peer){return NativeWebRtcManager.LOCAL_PEER.equals(peer)?screenSharing:screenSharers.contains(peer);}
    private String chooseScreenSharer(){if(screenSharing&&videoTiles.containsKey(NativeWebRtcManager.LOCAL_PEER))return NativeWebRtcManager.LOCAL_PEER;return screenSharers.stream().filter(videoTiles::containsKey).sorted().findFirst().orElse(null);}
    private String screenShareDisplayName(String peer){if(NativeWebRtcManager.LOCAL_PEER.equals(peer))return fullName+" (Bạn)";RosterRow rr=rosterByEmail.get(peer);return rr==null?peer:rr.name;}

    private void updateRoster(List<String[]> rows) {
        rosterModel.setRowCount(0);rosterByEmail.clear();
        for(String[]r:rows){
            if(r.length<7)continue;
            boolean sharing=r.length>=8&&"1".equals(r[7]);
            RosterRow rr=new RosterRow(Long.parseLong(r[0]),r[1],r[2].toLowerCase(Locale.ROOT),r[3],"1".equals(r[4]),"1".equals(r[5]),"1".equals(r[6]),sharing);
            rosterByEmail.put(rr.email,rr);
            if(!rr.email.equals(email)){if(rr.present&&rr.screen)screenSharers.add(rr.email);else screenSharers.remove(rr.email);}
            rosterModel.addRow(new Object[]{rr.name,displayRole(rr.role),rr.present?"CÓ MẶT":"CHƯA VÀO",rr.present?(rr.mic?"Bật":"Tắt"):"-",rr.present?(rr.cam?"Bật":"Tắt"):"-",rr.present?(rr.screen?"ĐANG CHIA SẺ":"-"):"-"});
            if(rr.present&&!rr.email.equals(email)){
                VideoTile tile=ensureVideoTile(rr.email,rr.name);
                if(rr.screen)tile.setState("Đang chia sẻ màn hình...");
                if(rtc!=null&&rtc.isInitialized()&&"TEACHER".equals(role)&&"STUDENT".equals(rr.role))rtc.ensurePeer(rr.email,true);
            }
        }
        // Remove remote video tiles of users no longer present.
        for(String key:new ArrayList<>(videoTiles.keySet())){if(NativeWebRtcManager.LOCAL_PEER.equals(key))continue;RosterRow rr=rosterByEmail.get(key);if(rr==null||!rr.present){if(rtc!=null)rtc.removePeer(key);removeVideoTile(key);}}
        if(activeScreenSharer==null||!isActuallySharing(activeScreenSharer))activeScreenSharer=chooseScreenSharer();
        applyVideoLayout();
    }

    private void sendChat(){String msg=Protocol.clean(txtChat.getText());if(msg.isBlank()||currentSessionId<=0)return;sendUdp("CHAT|"+Protocol.enc(msg));txtChat.setText("");}
    private void appendChat(String s){chatArea.append(s+"\n");chatArea.setCaretPosition(chatArea.getDocument().getLength());}
    private void toggleMic(){micOn=!micOn;if(rtc!=null)rtc.setMicrophoneEnabled(micOn);btnMic.setText(micOn?"🎤 Mic: BẬT":"🔇 Mic: TẮT");sendUdp("MEDIA_STATE|"+(micOn?1:0)+"|"+(camOn?1:0));}
    private void toggleCam(){camOn=!camOn;if(rtc!=null)rtc.setCameraEnabled(camOn);btnCam.setText(camOn?"📷 Camera: BẬT":"🚫 Camera: TẮT");VideoTile local=videoTiles.get(NativeWebRtcManager.LOCAL_PEER);if(local!=null&&!camOn&&!screenSharing){local.setImage(null);local.setState("Camera đang tắt");}sendUdp("MEDIA_STATE|"+(micOn?1:0)+"|"+(camOn?1:0));}

    private void toggleScreenShare(){
        if(rtc==null||!rtc.isInitialized()){error("WebRTC chưa sẵn sàng nên chưa thể chia sẻ màn hình.");return;}
        if(screenSharing){
            rtc.stopScreenShare();screenSharing=false;btnShareScreen.setText("🖥 Chia sẻ màn hình");
            VideoTile local=videoTiles.get(NativeWebRtcManager.LOCAL_PEER);if(local!=null&&!camOn){local.setImage(null);local.setState("Camera đang tắt");}
            if(NativeWebRtcManager.LOCAL_PEER.equals(activeScreenSharer))activeScreenSharer=chooseScreenSharer();applyVideoLayout();
            sendUdp("SCREEN_STATE|0");return;
        }
        List<NativeWebRtcManager.ScreenSource> screens=rtc.listScreens();
        if(screens.isEmpty()){error("Không tìm thấy màn hình để chia sẻ. Kiểm tra quyền Screen Capture của Windows và thư viện WebRTC Native.");return;}
        NativeWebRtcManager.ScreenSource selected=(NativeWebRtcManager.ScreenSource)JOptionPane.showInputDialog(this,"Chọn màn hình muốn chia sẻ:","Chia sẻ màn hình",JOptionPane.PLAIN_MESSAGE,null,screens.toArray(),screens.get(0));
        if(selected==null)return;
        if(rtc.startScreenShare(selected.id())){screenSharing=true;activeScreenSharer=NativeWebRtcManager.LOCAL_PEER;thumbnailsVisible=true;thumbnailScroll.setVisible(true);btnToggleThumbnails.setText("Ẩn camera nhỏ");btnShareScreen.setText("⏹ Dừng chia sẻ");applyVideoLayout();sendUdp("SCREEN_STATE|1");appendChat("[MÀN HÌNH] Bạn bắt đầu chia sẻ: "+selected.title());}
        else error("Không thể bắt đầu chia sẻ màn hình. Xem dòng [WEBRTC] trong khung chat để biết lỗi.");
    }

    private void handleScreenState(String[]p){
        if(p.length<4)return;String peer=Protocol.dec(p[1]).toLowerCase(Locale.ROOT);String name=Protocol.dec(p[2]);boolean on="1".equals(p[3]);
        if(peer.equals(email))return;
        appendChat("[MÀN HÌNH] "+name+(on?" bắt đầu chia sẻ màn hình.":" đã dừng chia sẻ màn hình."));
        if(on){screenSharers.add(peer);activeScreenSharer=peer;thumbnailsVisible=true;thumbnailScroll.setVisible(true);btnToggleThumbnails.setText("Ẩn camera nhỏ");VideoTile tile=videoTiles.get(peer);if(tile!=null)tile.setState("Đang chia sẻ màn hình...");}
        else{screenSharers.remove(peer);if(peer.equals(activeScreenSharer))activeScreenSharer=chooseScreenSharer();}
        applyVideoLayout();
    }

    private void chooseAndSendFile(){
        if(currentSessionId<=0)return;
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Chọn tệp gửi vào phòng học");
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Path file=chooser.getSelectedFile().toPath();
        CompletableFuture.runAsync(()->sendFileUdp(file));
    }

    private void sendFileUdp(Path file){
        try{
            long size=Files.size(file);if(size<=0){SwingUtilities.invokeLater(()->error("Tệp rỗng."));return;}if(size>MAX_FILE_SIZE){SwingUtilities.invokeLater(()->error("Tệp tối đa 20 MB trong bản demo UDP."));return;}
            byte[]all=Files.readAllBytes(file);int total=(all.length+FILE_CHUNK_SIZE-1)/FILE_CHUNK_SIZE;String id=Long.toHexString(System.currentTimeMillis())+Integer.toHexString(ThreadLocalRandom.current().nextInt());String name=file.getFileName().toString();
            boolean started=false;for(int attempt=0;attempt<3&&!started;attempt++){fileStartAcks.remove(id);sendUdp("FILE_START|"+id+"|"+Protocol.enc(name)+"|"+all.length+"|"+total);started=waitForSet(fileStartAcks,id,900);}
            if(!started)throw new IOException("Server không xác nhận bắt đầu gửi tệp");
            for(int seq=0;seq<total;seq++){
                int from=seq*FILE_CHUNK_SIZE,to=Math.min(all.length,from+FILE_CHUNK_SIZE);String data=Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(all,from,to));String ack=id+":"+seq;boolean ok=false;
                for(int attempt=0;attempt<4&&!ok;attempt++){fileChunkAcks.remove(ack);sendUdp("FILE_CHUNK|"+id+"|"+seq+"|"+data);ok=waitForSet(fileChunkAcks,ack,700);}
                if(!ok)throw new IOException("Mất gói tệp #"+seq+" sau nhiều lần gửi lại");
            }
            sendUdp("FILE_END|"+id);
        }catch(Exception e){SwingUtilities.invokeLater(()->error("Gửi tệp thất bại: "+e.getMessage()));}
    }

    private boolean waitForSet(Set<String>set,String key,long timeoutMs){long end=System.currentTimeMillis()+timeoutMs;while(System.currentTimeMillis()<end){if(set.remove(key))return true;try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}return set.remove(key);}

    private void handleIncomingFileStart(String[]p){
        if(p.length<7)return;try{String sender=Protocol.dec(p[1]).toLowerCase(Locale.ROOT);String senderName=Protocol.dec(p[2]);String id=p[3];String name=Protocol.dec(p[4]);long size=Long.parseLong(p[5]);int total=Integer.parseInt(p[6]);if(size<=0||size>MAX_FILE_SIZE||total<=0)return;String key=sender+"|"+id;IncomingFile created=new IncomingFile(sender,senderName,id,sanitizeFileName(name),size,total);IncomingFile previous=incomingFiles.putIfAbsent(key,created);if(previous==null)appendChat("[TỆP] "+senderName+" đang gửi: "+name+" ("+humanBytes(size)+")");}catch(Exception ignored){}
    }

    private void handleIncomingFileChunk(String[]p){
        if(p.length<5)return;try{String sender=Protocol.dec(p[1]).toLowerCase(Locale.ROOT);String id=p[2];int seq=Integer.parseInt(p[3]);IncomingFile f=incomingFiles.get(sender+"|"+id);if(f==null||seq<0||seq>=f.totalChunks)return;f.chunks.putIfAbsent(seq,Base64.getUrlDecoder().decode(p[4]));}catch(Exception ignored){}
    }

    private void handleIncomingFileEnd(String[]p){
        if(p.length<3)return;String sender=Protocol.dec(p[1]).toLowerCase(Locale.ROOT);String id=p[2];IncomingFile f=incomingFiles.get(sender+"|"+id);if(f==null)return;
        List<Integer>missing=new ArrayList<>();for(int i=0;i<f.totalChunks;i++)if(!f.chunks.containsKey(i))missing.add(i);
        if(!missing.isEmpty()){String csv=missing.stream().limit(2000).map(String::valueOf).reduce((a,b)->a+","+b).orElse("");sendUdp("FILE_MISSING|"+Protocol.enc(sender)+"|"+id+"|"+csv);return;}
        incomingFiles.remove(sender+"|"+id);
        CompletableFuture.runAsync(()->saveIncomingFile(f));
    }

    private void saveIncomingFile(IncomingFile f){
        try{ByteArrayOutputStream out=new ByteArrayOutputStream((int)Math.min(Integer.MAX_VALUE,f.size));for(int i=0;i<f.totalChunks;i++)out.write(f.chunks.get(i));byte[]data=out.toByteArray();if(data.length!=f.size)throw new IOException("Kích thước nhận được không khớp");Path dir=Paths.get(System.getProperty("user.home"),"Downloads","SmartClassroomReceived");Files.createDirectories(dir);Path target=uniquePath(dir,f.fileName);Files.write(target,data);sendUdp("FILE_RECEIVED|"+Protocol.enc(f.senderEmail)+"|"+f.id);SwingUtilities.invokeLater(()->appendChat("[TỆP] Đã nhận từ "+f.senderName+": "+f.fileName+" → "+target));}catch(Exception e){SwingUtilities.invokeLater(()->error("Không lưu được tệp nhận: "+e.getMessage()));}
    }

    private static Path uniquePath(Path dir,String name){Path p=dir.resolve(name);if(!Files.exists(p))return p;String base=name,ext="";int dot=name.lastIndexOf('.');if(dot>0){base=name.substring(0,dot);ext=name.substring(dot);}for(int i=1;i<10000;i++){Path c=dir.resolve(base+" ("+i+")"+ext);if(!Files.exists(c))return c;}return dir.resolve(System.currentTimeMillis()+"_"+name);}
    private static String sanitizeFileName(String name){if(name==null||name.isBlank())return "tep_nhan";return name.replace('\\','_').replace('/','_').replace(':','_').replace('*','_').replace('?','_').replace('"','_').replace('<','_').replace('>','_').replace('|','_');}
    private static String humanBytes(long n){if(n<1024)return n+" B";if(n<1024*1024)return String.format(Locale.ROOT,"%.1f KB",n/1024.0);return String.format(Locale.ROOT,"%.1f MB",n/(1024.0*1024));}

    private void showAttendanceReport(String[]p){if(p.length<4)return;String taken=Protocol.dec(p[2]);List<String[]>rows=Protocol.unpackRows(p[3]);DefaultTableModel m=model(new Object[]{"Họ và tên","Email","Trạng thái","Thời gian"});int absent=0;for(String[]r:rows){if(r.length<5)continue;String st="PRESENT".equals(r[3])?"CÓ":"VẮNG";if("VẮNG".equals(st))absent++;m.addRow(new Object[]{r[1],r[2],st,r[4]});}JTable t=new JTable(m);t.setRowHeight(26);JPanel panel=new JPanel(new BorderLayout(6,6));panel.add(new JLabel("Phiếu điểm danh lúc "+taken+" | Tổng: "+rows.size()+" | Vắng: "+absent),BorderLayout.NORTH);panel.add(new JScrollPane(t),BorderLayout.CENTER);panel.setPreferredSize(new Dimension(760,430));JOptionPane.showMessageDialog(this,panel,"Kết quả điểm danh",JOptionPane.INFORMATION_MESSAGE);}

    private void showAttendanceHistory(){int row=myClassTable.getSelectedRow();if(row<0||row>=myClasses.size()){info("Chọn một lớp để xem lịch sử điểm danh.");return;}ClassRow c=myClasses.get(row);asyncRequest("ATTENDANCE_HISTORY|"+token+"|"+c.id,r->{if(!r.startsWith("ATTENDANCE_HISTORY|")){error(responseError(r));return;}List<String[]>rows=Protocol.unpackRows(r.substring("ATTENDANCE_HISTORY|".length()));DefaultTableModel m=model(new Object[]{"Mã phiếu","Session","Thời gian","Có","Vắng"});for(String[]x:rows)if(x.length>=5)m.addRow(x);JTable table=new JTable(m);table.setRowHeight(26);JButton detail=new JButton("Xem chi tiết");JPanel panel=new JPanel(new BorderLayout(6,6));panel.add(new JScrollPane(table),BorderLayout.CENTER);panel.add(detail,BorderLayout.SOUTH);panel.setPreferredSize(new Dimension(700,400));JDialog d=new JDialog(this,"Lịch sử điểm danh - "+c.name,false);d.setContentPane(panel);d.pack();d.setLocationRelativeTo(this);detail.addActionListener(ev->{int rr=table.getSelectedRow();if(rr<0)return;long id=Long.parseLong(String.valueOf(m.getValueAt(rr,0)));showAttendanceDetail(id);});d.setVisible(true);});}
    private void showAttendanceDetail(long id){asyncRequest("ATTENDANCE_DETAIL|"+token+"|"+id,r->{if(!r.startsWith("ATTENDANCE_DETAIL|")){error(responseError(r));return;}DefaultTableModel m=model(new Object[]{"Họ và tên","Email","Trạng thái","Thời gian"});for(String[]x:Protocol.unpackRows(r.substring("ATTENDANCE_DETAIL|".length())))if(x.length>=5)m.addRow(new Object[]{x[1],x[2],"PRESENT".equals(x[3])?"CÓ":"VẮNG",x[4]});JTable t=new JTable(m);t.setRowHeight(26);JScrollPane sp=new JScrollPane(t);sp.setPreferredSize(new Dimension(700,420));JOptionPane.showMessageDialog(this,sp,"Chi tiết phiếu điểm danh #"+id,JOptionPane.INFORMATION_MESSAGE);});}

    private void leaveMeeting(){if(currentSessionId<=0)return;long sid=currentSessionId;sendUdp("LEAVE_SESSION");if("TEACHER".equals(role))asyncRequest("CLOSE_SESSION|"+token+"|"+sid,r->{});closeRtc();clearMeeting();cardsLayout.show(cards,"LOBBY");refreshLobby();}
    private void clearMeeting(){currentSessionId=0;currentClassId=0;currentClassName=null;currentClassCode=null;currentTeacherEmail=null;screenSharing=false;activeScreenSharer=null;screenSharers.clear();thumbnailsVisible=true;incomingFiles.clear();fileStartAcks.clear();fileChunkAcks.clear();btnShareScreen.setText("🖥 Chia sẻ màn hình");btnToggleThumbnails.setText("Ẩn camera nhỏ");rosterByEmail.clear();rosterModel.setRowCount(0);for(VideoTile t:videoTiles.values())if(t.getParent()!=null)t.getParent().remove(t);videoTiles.clear();videoGrid.removeAll();thumbnailStrip.removeAll();sharedScreenHost.removeAll();videoStageLayout.show(videoStage,"GRID");videoGrid.revalidate();thumbnailStrip.revalidate();sharedScreenHost.revalidate();videoStage.revalidate();videoGrid.repaint();videoStage.repaint();}
    private void closeRtc(){if(rtc!=null){try{rtc.close();}catch(Exception ignored){}rtc=null;}}

    private void logout(){try{if(token!=null)tcpRequest("LOGOUT|"+token);}catch(Exception ignored){}sendUdp("LOGOUT");disconnectLocal();token=null;userId=0;fullName=email=role=null;cardsLayout.show(cards,"LOGIN");}
    private void disconnectLocal(){closeRtc();udpRunning=false;if(udpSocket!=null&&!udpSocket.isClosed())udpSocket.close();udpSocket=null;clearMeeting();}
    private void shutdown(){
        long sid = currentSessionId;
        if(sid>0){
            try{sendUdp("LEAVE_SESSION");}catch(Exception ignored){}
            if("TEACHER".equals(role) && token != null){try{tcpRequest("CLOSE_SESSION|"+token+"|"+sid);}catch(Exception ignored){}}
        }
        if(token!=null){try{tcpRequest("LOGOUT|"+token);}catch(Exception ignored){}}
        sendUdp("LOGOUT");disconnectLocal();heartbeat.shutdownNow();
    }

    private String tcpRequest(String request) throws IOException {
        try(Socket s=new Socket()){s.connect(new InetSocketAddress(serverHost,tcpPort),3000);s.setSoTimeout(10000);try(BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));BufferedWriter out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8))){out.write(request);out.newLine();out.flush();String r=in.readLine();if(r==null)throw new IOException("Server không trả dữ liệu");return r;}}
    }
    private void asyncRequest(String req,java.util.function.Consumer<String> onDone){CompletableFuture.supplyAsync(()->{try{return tcpRequest(req);}catch(Exception e){return "ERROR|"+Protocol.enc(e.getMessage());}}).thenAccept(r->SwingUtilities.invokeLater(()->onDone.accept(r)));}
    private void sendUdp(String message){if(!udpRunning||udpSocket==null||serverAddress==null)return;try{byte[]data=message.getBytes(StandardCharsets.UTF_8);udpSocket.send(new DatagramPacket(data,data.length,serverAddress,udpPort));}catch(Exception e){SwingUtilities.invokeLater(()->appendChat("[LỖI GỬI UDP] "+e.getMessage()));}}

    private String responseError(String response){String[]p=response.split("\\|",-1);if(p.length>1&&(p[0].equals("ERROR")||p[0].equals("LOGIN_FAIL")||p[0].equals("OTP_FAIL"))){try{return Protocol.dec(p[p.length-1]);}catch(Exception ignored){}}return response;}
    private String displayRole(String r){return "TEACHER".equals(r)?"GIÁO VIÊN":"HỌC SINH";}
    private void info(String s){JOptionPane.showMessageDialog(this,s,"Smart Classroom",JOptionPane.INFORMATION_MESSAGE);}
    private void error(String s){JOptionPane.showMessageDialog(this,s,"Lỗi",JOptionPane.ERROR_MESSAGE);}

    private record ClassRow(long id,String name,String code,String teacher,int students,long activeSessionId,String status){}
    private record RosterRow(long id,String name,String email,String role,boolean present,boolean mic,boolean cam,boolean screen){}
    private static final class IncomingFile {
        final String senderEmail,senderName,id,fileName;final long size;final int totalChunks;final Map<Integer,byte[]>chunks=new ConcurrentHashMap<>();
        IncomingFile(String senderEmail,String senderName,String id,String fileName,long size,int totalChunks){this.senderEmail=senderEmail;this.senderName=senderName;this.id=id;this.fileName=fileName;this.size=size;this.totalChunks=totalChunks;}
    }
}
