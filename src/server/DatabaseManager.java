package server;

import common.PasswordUtil;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DatabaseManager {
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public DatabaseManager(String host, int port, String database, String user, String password) {
        this.host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        this.port = port <= 0 ? 3306 : port;
        this.database = sanitizeDatabaseName(database == null || database.isBlank() ? "smart_classroom" : database.trim());
        this.user = user == null ? "root" : user.trim();
        this.password = password == null ? "" : password;
    }

    private static String sanitizeDatabaseName(String name) {
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Tên database chỉ được chứa chữ, số và dấu gạch dưới");
        }
        return name;
    }

    private String baseUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private String dbUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&useSSL=false";
    }

    public void initialize() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(baseUrl(), user, password);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      full_name VARCHAR(160) NOT NULL,
                      phone VARCHAR(30) NOT NULL,
                      email VARCHAR(190) NOT NULL UNIQUE,
                      password_hash VARCHAR(255) NOT NULL,
                      requested_role VARCHAR(20) NOT NULL,
                      role VARCHAR(20) NULL,
                      account_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      approved_at DATETIME NULL,
                      last_login DATETIME NULL,
                      INDEX idx_users_status(account_status),
                      INDEX idx_users_role(role)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS classes (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      class_name VARCHAR(180) NOT NULL,
                      class_code VARCHAR(80) NOT NULL UNIQUE,
                      teacher_id BIGINT NOT NULL,
                      status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT fk_classes_teacher FOREIGN KEY (teacher_id) REFERENCES users(id),
                      INDEX idx_classes_teacher(teacher_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS class_enrollments (
                      class_id BIGINT NOT NULL,
                      student_id BIGINT NOT NULL,
                      enrolled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY(class_id, student_id),
                      CONSTRAINT fk_enroll_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE,
                      CONSTRAINT fk_enroll_student FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
                      INDEX idx_enroll_student(student_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS class_sessions (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      class_id BIGINT NOT NULL,
                      started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      ended_at DATETIME NULL,
                      status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
                      CONSTRAINT fk_session_class FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE,
                      INDEX idx_session_class_status(class_id, status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS attendance_sessions (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      class_session_id BIGINT NOT NULL,
                      taken_by BIGINT NOT NULL,
                      taken_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT fk_att_session FOREIGN KEY (class_session_id) REFERENCES class_sessions(id) ON DELETE CASCADE,
                      CONSTRAINT fk_att_taken_by FOREIGN KEY (taken_by) REFERENCES users(id),
                      INDEX idx_att_class_session(class_session_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS attendance_records (
                      attendance_session_id BIGINT NOT NULL,
                      student_id BIGINT NOT NULL,
                      status VARCHAR(20) NOT NULL,
                      recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY(attendance_session_id, student_id),
                      CONSTRAINT fk_att_record_session FOREIGN KEY (attendance_session_id) REFERENCES attendance_sessions(id) ON DELETE CASCADE,
                      CONSTRAINT fk_att_record_student FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
                      INDEX idx_att_record_student(student_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS password_reset_otps (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      user_id BIGINT NOT NULL,
                      code_hash CHAR(64) NOT NULL,
                      reset_token VARCHAR(80) NULL,
                      expires_at DATETIME NOT NULL,
                      verified_at DATETIME NULL,
                      used BOOLEAN NOT NULL DEFAULT FALSE,
                      attempts INT NOT NULL DEFAULT 0,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT fk_reset_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                      INDEX idx_reset_user(user_id),
                      UNIQUE KEY uq_reset_token(reset_token)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(dbUrl(), user, password);
    }

    public RegisterResult register(String fullName, String phone, String email, String rawPassword, String requestedRole) throws SQLException {
        fullName = fullName == null ? "" : fullName.trim();
        phone = phone == null ? "" : phone.trim();
        email = normalizeEmail(email);
        requestedRole = normalizeRequestedRole(requestedRole);
        if (fullName.length() < 2) return new RegisterResult(false, "Họ và tên không hợp lệ");
        if (!phone.matches("[0-9+ .()-]{8,20}")) return new RegisterResult(false, "Số điện thoại không hợp lệ");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return new RegisterResult(false, "Email không hợp lệ");
        if (rawPassword == null || rawPassword.length() < 6) return new RegisterResult(false, "Mật khẩu phải có ít nhất 6 ký tự");

        try (Connection c = connection()) {
            try (PreparedStatement check = c.prepareStatement("SELECT id FROM users WHERE email=?")) {
                check.setString(1, email);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) return new RegisterResult(false, "Email đã được đăng ký");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO users(full_name, phone, email, password_hash, requested_role, account_status)
                    VALUES(?,?,?,?,?,'PENDING')
                    """)) {
                ps.setString(1, fullName);
                ps.setString(2, phone);
                ps.setString(3, email);
                ps.setString(4, PasswordUtil.hash(rawPassword));
                ps.setString(5, requestedRole);
                ps.executeUpdate();
            }
        }
        return new RegisterResult(true, "Đăng ký thành công. Tài khoản đang chờ Server duyệt vai trò.");
    }

    public LoginResult login(String email, String rawPassword) throws SQLException {
        email = normalizeEmail(email);
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE email=?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new LoginResult(false, "NOT_FOUND", null);
                UserRecord u = userFrom(rs);
                if (!PasswordUtil.verify(rawPassword == null ? "" : rawPassword, u.passwordHash())) {
                    return new LoginResult(false, "WRONG_PASSWORD", null);
                }
                if (!"ACTIVE".equals(u.status()) || u.role() == null || u.role().isBlank()) {
                    return new LoginResult(false, u.status(), u);
                }
                try (PreparedStatement up = c.prepareStatement("UPDATE users SET last_login=NOW() WHERE id=?")) {
                    up.setLong(1, u.id());
                    up.executeUpdate();
                }
                return new LoginResult(true, "OK", new UserRecord(u.id(), u.fullName(), u.phone(), u.email(), u.passwordHash(), u.requestedRole(), u.role(), u.status(), u.createdAt(), u.approvedAt(), LocalDateTime.now()));
            }
        }
    }

    public List<UserRecord> pendingUsers() throws SQLException {
        return usersByStatus("PENDING");
    }

    public List<UserRecord> allUsers() throws SQLException {
        List<UserRecord> out = new ArrayList<>();
        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM users ORDER BY full_name, email")) {
            while (rs.next()) out.add(userFrom(rs));
        }
        sortUsersVi(out);
        return out;
    }

    private List<UserRecord> usersByStatus(String status) throws SQLException {
        List<UserRecord> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE account_status=?")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(userFrom(rs));
            }
        }
        sortUsersVi(out);
        return out;
    }

    public void approveUser(long userId, String role) throws SQLException {
        role = normalizeRequestedRole(role);
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("UPDATE users SET role=?, account_status='ACTIVE', approved_at=NOW() WHERE id=?")) {
            ps.setString(1, role);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void rejectUser(long userId) throws SQLException {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("UPDATE users SET account_status='REJECTED', role=NULL WHERE id=?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    public void setAccountStatus(long userId, String status) throws SQLException {
        if (!Set.of("ACTIVE", "LOCKED", "REJECTED", "PENDING").contains(status)) throw new IllegalArgumentException("Trạng thái không hợp lệ");
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("UPDATE users SET account_status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public Optional<UserRecord> getUser(long id) throws SQLException {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(userFrom(rs)) : Optional.empty();
            }
        }
    }

    public ClassRecord createClass(long teacherId, String className, String classCode) throws SQLException {
        className = className == null ? "" : className.trim();
        classCode = normalizeClassCode(classCode);
        if (className.length() < 2) throw new IllegalArgumentException("Tên lớp không hợp lệ");
        if (classCode.length() < 2) throw new IllegalArgumentException("Mã lớp không hợp lệ");
        try (Connection c = connection()) {
            try (PreparedStatement check = c.prepareStatement("SELECT id FROM classes WHERE class_code=?")) {
                check.setString(1, classCode);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) throw new IllegalArgumentException("Mã lớp đã tồn tại");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO classes(class_name,class_code,teacher_id) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, className);
                ps.setString(2, classCode);
                ps.setLong(3, teacherId);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Không lấy được ID lớp");
                    return getClassById(keys.getLong(1)).orElseThrow();
                }
            }
        }
    }

    public void enrollStudent(long studentId, long classId) throws SQLException {
        try (Connection c = connection()) {
            try (PreparedStatement role = c.prepareStatement("SELECT role,account_status FROM users WHERE id=?")) {
                role.setLong(1, studentId);
                try (ResultSet rs = role.executeQuery()) {
                    if (!rs.next() || !"STUDENT".equals(rs.getString("role")) || !"ACTIVE".equals(rs.getString("account_status"))) {
                        throw new IllegalArgumentException("Chỉ tài khoản HỌC SINH đang hoạt động mới được đăng ký lớp");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO class_enrollments(class_id,student_id) VALUES(?,?)")) {
                ps.setLong(1, classId);
                ps.setLong(2, studentId);
                ps.executeUpdate();
            }
        }
    }

    public List<ClassRecord> teacherClasses(long teacherId) throws SQLException {
        return queryClasses("WHERE c.teacher_id=?", teacherId);
    }

    public List<ClassRecord> studentClasses(long studentId) throws SQLException {
        return queryClasses("JOIN class_enrollments e ON e.class_id=c.id WHERE e.student_id=?", studentId);
    }

    public List<ClassRecord> availableClasses(long studentId) throws SQLException {
        return queryClasses("WHERE c.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM class_enrollments e WHERE e.class_id=c.id AND e.student_id=?)", studentId);
    }

    public List<ClassRecord> allClasses() throws SQLException {
        return queryClasses("", null);
    }

    private List<ClassRecord> queryClasses(String extra, Long param) throws SQLException {
        String sql = """
                SELECT c.id,c.class_name,c.class_code,c.teacher_id,c.status,c.created_at,
                       u.full_name AS teacher_name,
                       (SELECT COUNT(*) FROM class_enrollments e2 WHERE e2.class_id=c.id) AS student_count,
                       (SELECT s.id FROM class_sessions s WHERE s.class_id=c.id AND s.status='OPEN' ORDER BY s.id DESC LIMIT 1) AS active_session_id
                FROM classes c JOIN users u ON u.id=c.teacher_id
                """ + extra + " ORDER BY c.class_name,c.class_code";
        List<ClassRecord> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long active = rs.getLong("active_session_id");
                    if (rs.wasNull()) active = 0;
                    out.add(new ClassRecord(
                            rs.getLong("id"), rs.getString("class_name"), rs.getString("class_code"),
                            rs.getLong("teacher_id"), rs.getString("teacher_name"), rs.getInt("student_count"),
                            active, rs.getString("status"), toLocal(rs.getTimestamp("created_at"))));
                }
            }
        }
        out.sort(Comparator.comparing(ClassRecord::className, viComparator()).thenComparing(ClassRecord::classCode));
        return out;
    }

    public Optional<ClassRecord> getClassById(long classId) throws SQLException {
        return allClasses().stream().filter(x -> x.id() == classId).findFirst();
    }

    public SessionRecord openSession(long teacherId, long classId) throws SQLException {
        ClassRecord clazz = getClassById(classId).orElseThrow(() -> new IllegalArgumentException("Lớp không tồn tại"));
        if (clazz.teacherId() != teacherId) throw new IllegalArgumentException("Bạn không phải giáo viên của lớp này");
        if (clazz.activeSessionId() > 0) return getSession(clazz.activeSessionId()).orElseThrow();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("INSERT INTO class_sessions(class_id,status) VALUES(?,'OPEN')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, classId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Không tạo được buổi học");
                return getSession(keys.getLong(1)).orElseThrow();
            }
        }
    }

    public void closeSession(long teacherId, long sessionId) throws SQLException {
        SessionRecord s = getSession(sessionId).orElseThrow(() -> new IllegalArgumentException("Buổi học không tồn tại"));
        ClassRecord c = getClassById(s.classId()).orElseThrow();
        if (c.teacherId() != teacherId) throw new IllegalArgumentException("Bạn không phải giáo viên của lớp này");
        try (Connection cn = connection(); PreparedStatement ps = cn.prepareStatement("UPDATE class_sessions SET status='CLOSED',ended_at=NOW() WHERE id=?")) {
            ps.setLong(1, sessionId);
            ps.executeUpdate();
        }
    }

    public Optional<SessionRecord> getSession(long sessionId) throws SQLException {
        String sql = """
                SELECT s.id,s.class_id,s.started_at,s.ended_at,s.status,c.class_name,c.class_code,c.teacher_id,u.full_name AS teacher_name
                FROM class_sessions s
                JOIN classes c ON c.id=s.class_id
                JOIN users u ON u.id=c.teacher_id
                WHERE s.id=?
                """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(sessionFrom(rs));
            }
        }
    }

    public List<SessionRecord> openSessions() throws SQLException {
        String sql = """
                SELECT s.id,s.class_id,s.started_at,s.ended_at,s.status,c.class_name,c.class_code,c.teacher_id,u.full_name AS teacher_name
                FROM class_sessions s JOIN classes c ON c.id=s.class_id JOIN users u ON u.id=c.teacher_id
                WHERE s.status='OPEN' ORDER BY s.started_at DESC
                """;
        List<SessionRecord> out = new ArrayList<>();
        try (Connection c = connection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(sessionFrom(rs));
        }
        return out;
    }

    public boolean canJoinSession(long userId, String role, long sessionId) throws SQLException {
        Optional<SessionRecord> os = getSession(sessionId);
        if (os.isEmpty() || !"OPEN".equals(os.get().status())) return false;
        SessionRecord s = os.get();
        if ("TEACHER".equals(role)) return s.teacherId() == userId;
        if (!"STUDENT".equals(role)) return false;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM class_enrollments WHERE class_id=? AND student_id=?")) {
            ps.setLong(1, s.classId());
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public List<RosterStudent> classRoster(long classId) throws SQLException {
        String sql = """
                SELECT u.id,u.full_name,u.email,u.phone
                FROM class_enrollments e JOIN users u ON u.id=e.student_id
                WHERE e.class_id=? AND u.account_status='ACTIVE' AND u.role='STUDENT'
                """;
        List<RosterStudent> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new RosterStudent(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone")));
            }
        }
        out.sort(Comparator.comparing(RosterStudent::fullName, viComparator()).thenComparing(RosterStudent::email));
        return out;
    }

    public AttendanceResult takeAttendance(long sessionId, long teacherId, Set<Long> presentStudentIds) throws SQLException {
        SessionRecord session = getSession(sessionId).orElseThrow(() -> new IllegalArgumentException("Buổi học không tồn tại"));
        if (session.teacherId() != teacherId) throw new IllegalArgumentException("Chỉ giáo viên của lớp mới được điểm danh");
        if (!"OPEN".equals(session.status())) throw new IllegalArgumentException("Buổi học đã kết thúc");
        List<RosterStudent> roster = classRoster(session.classId());
        LocalDateTime now = LocalDateTime.now();
        long attendanceId;
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO attendance_sessions(class_session_id,taken_by,taken_at) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, sessionId);
                    ps.setLong(2, teacherId);
                    ps.setTimestamp(3, Timestamp.valueOf(now));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Không tạo được phiếu điểm danh");
                        attendanceId = keys.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO attendance_records(attendance_session_id,student_id,status,recorded_at) VALUES(?,?,?,?)")) {
                    for (RosterStudent s : roster) {
                        ps.setLong(1, attendanceId);
                        ps.setLong(2, s.id());
                        ps.setString(3, presentStudentIds.contains(s.id()) ? "PRESENT" : "ABSENT");
                        ps.setTimestamp(4, Timestamp.valueOf(now));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException se) throw se;
                throw new SQLException(e);
            } finally {
                c.setAutoCommit(true);
            }
        }
        List<AttendanceItem> items = new ArrayList<>();
        for (RosterStudent s : roster) {
            items.add(new AttendanceItem(s.id(), s.fullName(), s.email(), presentStudentIds.contains(s.id()) ? "PRESENT" : "ABSENT", now));
        }
        return new AttendanceResult(attendanceId, now, items);
    }

    public List<AttendanceSummary> attendanceHistory(long classId) throws SQLException {
        String sql = """
                SELECT a.id,a.taken_at,s.id AS session_id,
                       SUM(CASE WHEN r.status='PRESENT' THEN 1 ELSE 0 END) present_count,
                       SUM(CASE WHEN r.status='ABSENT' THEN 1 ELSE 0 END) absent_count
                FROM attendance_sessions a
                JOIN class_sessions s ON s.id=a.class_session_id
                JOIN attendance_records r ON r.attendance_session_id=a.id
                WHERE s.class_id=? GROUP BY a.id,a.taken_at,s.id ORDER BY a.taken_at DESC
                """;
        List<AttendanceSummary> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new AttendanceSummary(rs.getLong("id"), rs.getLong("session_id"), toLocal(rs.getTimestamp("taken_at")), rs.getInt("present_count"), rs.getInt("absent_count")));
            }
        }
        return out;
    }

    public List<AttendanceItem> attendanceDetail(long attendanceId) throws SQLException {
        String sql = """
                SELECT u.id,u.full_name,u.email,r.status,r.recorded_at
                FROM attendance_records r JOIN users u ON u.id=r.student_id
                WHERE r.attendance_session_id=?
                """;
        List<AttendanceItem> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attendanceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new AttendanceItem(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"), rs.getString("status"), toLocal(rs.getTimestamp("recorded_at"))));
            }
        }
        out.sort(Comparator.comparing(AttendanceItem::fullName, viComparator()).thenComparing(AttendanceItem::email));
        return out;
    }

    public Optional<UserRecord> findUserByEmail(String email) throws SQLException {
        email = normalizeEmail(email);
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE email=?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(userFrom(rs)) : Optional.empty();
            }
        }
    }

    public ResetRequestResult createPasswordReset(String email, String plainCode, int expiryMinutes) throws SQLException {
        email = normalizeEmail(email);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return new ResetRequestResult(false, "Email không hợp lệ", null);
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                UserRecord u;
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE email=? FOR UPDATE")) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new ResetRequestResult(false, "Email chưa được đăng ký", null); }
                        u = userFrom(rs);
                    }
                }
                try (PreparedStatement clear = c.prepareStatement("UPDATE password_reset_otps SET used=TRUE WHERE user_id=? AND used=FALSE")) {
                    clear.setLong(1, u.id()); clear.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO password_reset_otps(user_id,code_hash,expires_at) VALUES(?,?,?)")) {
                    ps.setLong(1, u.id());
                    ps.setString(2, sha256(plainCode));
                    ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now().plusMinutes(expiryMinutes)));
                    ps.executeUpdate();
                }
                c.commit();
                return new ResetRequestResult(true, "Mã xác minh đã được tạo", u);
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException se) throw se;
                throw new SQLException(e);
            } finally { c.setAutoCommit(true); }
        }
    }

    public ResetVerifyResult verifyPasswordResetOtp(String email, String plainCode) throws SQLException {
        email = normalizeEmail(email);
        if (plainCode == null || !plainCode.matches("\\d{6}")) return new ResetVerifyResult(false, "INVALID", null, "Mã xác minh phải gồm 6 chữ số");
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                long resetId;
                int attempts;
                String storedHash;
                Timestamp expires;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT r.id,r.code_hash,r.expires_at,r.attempts
                        FROM password_reset_otps r JOIN users u ON u.id=r.user_id
                        WHERE u.email=? AND r.used=FALSE
                        ORDER BY r.id DESC LIMIT 1 FOR UPDATE
                        """)) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new ResetVerifyResult(false, "NOT_FOUND", null, "Chưa có yêu cầu đặt lại mật khẩu"); }
                        resetId = rs.getLong("id");
                        storedHash = rs.getString("code_hash");
                        expires = rs.getTimestamp("expires_at");
                        attempts = rs.getInt("attempts");
                    }
                }
                if (expires == null || expires.toLocalDateTime().isBefore(LocalDateTime.now())) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE password_reset_otps SET used=TRUE WHERE id=?")) { ps.setLong(1, resetId); ps.executeUpdate(); }
                    c.commit();
                    return new ResetVerifyResult(false, "EXPIRED", null, "Mã xác minh đã hết hạn. Vui lòng gửi mã mới.");
                }
                if (attempts >= 5) {
                    c.rollback();
                    return new ResetVerifyResult(false, "TOO_MANY_ATTEMPTS", null, "Bạn đã nhập sai quá nhiều lần. Vui lòng gửi mã mới.");
                }
                if (!MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII), sha256(plainCode).getBytes(StandardCharsets.US_ASCII))) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE password_reset_otps SET attempts=attempts+1 WHERE id=?")) { ps.setLong(1, resetId); ps.executeUpdate(); }
                    c.commit();
                    return new ResetVerifyResult(false, "WRONG_CODE", null, "Mã xác minh không đúng");
                }
                String resetToken = UUID.randomUUID().toString();
                try (PreparedStatement ps = c.prepareStatement("UPDATE password_reset_otps SET reset_token=?,verified_at=NOW(),expires_at=DATE_ADD(NOW(), INTERVAL 10 MINUTE) WHERE id=?")) {
                    ps.setString(1, resetToken); ps.setLong(2, resetId); ps.executeUpdate();
                }
                c.commit();
                return new ResetVerifyResult(true, "OK", resetToken, "Mã xác minh chính xác");
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException se) throw se;
                throw new SQLException(e);
            } finally { c.setAutoCommit(true); }
        }
    }

    public ResetPasswordResult resetPassword(String resetToken, String newPassword) throws SQLException {
        if (resetToken == null || resetToken.isBlank()) return new ResetPasswordResult(false, "Phiên đặt lại mật khẩu không hợp lệ");
        if (newPassword == null || newPassword.length() < 6) return new ResetPasswordResult(false, "Mật khẩu mới phải có ít nhất 6 ký tự");
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                long resetId, userId;
                Timestamp expires, verified;
                try (PreparedStatement ps = c.prepareStatement("SELECT id,user_id,expires_at,verified_at,used FROM password_reset_otps WHERE reset_token=? FOR UPDATE")) {
                    ps.setString(1, resetToken);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new ResetPasswordResult(false, "Phiên đặt lại mật khẩu không tồn tại"); }
                        resetId = rs.getLong("id"); userId = rs.getLong("user_id"); expires = rs.getTimestamp("expires_at"); verified = rs.getTimestamp("verified_at");
                        if (rs.getBoolean("used")) { c.rollback(); return new ResetPasswordResult(false, "Mã đặt lại mật khẩu đã được sử dụng"); }
                    }
                }
                if (verified == null || expires == null || expires.toLocalDateTime().isBefore(LocalDateTime.now())) {
                    c.rollback(); return new ResetPasswordResult(false, "Phiên đặt lại mật khẩu đã hết hạn");
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash=? WHERE id=?")) {
                    ps.setString(1, PasswordUtil.hash(newPassword)); ps.setLong(2, userId); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE password_reset_otps SET used=TRUE WHERE user_id=?")) {
                    ps.setLong(1, userId); ps.executeUpdate();
                }
                c.commit();
                return new ResetPasswordResult(true, "Đổi mật khẩu thành công. Bạn có thể đăng nhập bằng mật khẩu mới.");
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException se) throw se;
                throw new SQLException(e);
            } finally { c.setAutoCommit(true); }
        }
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : hash) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException("Không thể băm OTP", e); }
    }

    private SessionRecord sessionFrom(ResultSet rs) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        return new SessionRecord(rs.getLong("id"), rs.getLong("class_id"), rs.getString("class_name"), rs.getString("class_code"), rs.getLong("teacher_id"), rs.getString("teacher_name"), toLocal(rs.getTimestamp("started_at")), ended == null ? null : ended.toLocalDateTime(), rs.getString("status"));
    }

    private UserRecord userFrom(ResultSet rs) throws SQLException {
        Timestamp approved = rs.getTimestamp("approved_at");
        Timestamp login = rs.getTimestamp("last_login");
        return new UserRecord(rs.getLong("id"), rs.getString("full_name"), rs.getString("phone"), normalizeEmail(rs.getString("email")), rs.getString("password_hash"), rs.getString("requested_role"), rs.getString("role"), rs.getString("account_status"), toLocal(rs.getTimestamp("created_at")), approved == null ? null : approved.toLocalDateTime(), login == null ? null : login.toLocalDateTime());
    }

    private static LocalDateTime toLocal(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
    private static String normalizeEmail(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }
    private static String normalizeRequestedRole(String role) {
        role = role == null ? "STUDENT" : role.trim().toUpperCase(Locale.ROOT);
        return "TEACHER".equals(role) ? "TEACHER" : "STUDENT";
    }
    private static String normalizeClassCode(String code) { return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replace("|", ""); }


    private static void sortUsersVi(List<UserRecord> users) {
        users.sort(Comparator.comparing(UserRecord::fullName, viComparator()).thenComparing(UserRecord::email));
    }

    public static Comparator<String> viComparator() {
        java.text.Collator collator = java.text.Collator.getInstance(new Locale("vi", "VN"));
        collator.setStrength(java.text.Collator.PRIMARY);
        return collator::compare;
    }

    public static String displayTime(LocalDateTime dt) { return dt == null ? "" : dt.format(DISPLAY_TIME); }

    public record RegisterResult(boolean ok, String message) {}
    public record LoginResult(boolean ok, String code, UserRecord user) {}
    public record UserRecord(long id, String fullName, String phone, String email, String passwordHash, String requestedRole, String role, String status, LocalDateTime createdAt, LocalDateTime approvedAt, LocalDateTime lastLogin) {}
    public record ClassRecord(long id, String className, String classCode, long teacherId, String teacherName, int studentCount, long activeSessionId, String status, LocalDateTime createdAt) {}
    public record SessionRecord(long id, long classId, String className, String classCode, long teacherId, String teacherName, LocalDateTime startedAt, LocalDateTime endedAt, String status) {}
    public record RosterStudent(long id, String fullName, String email, String phone) {}
    public record AttendanceItem(long studentId, String fullName, String email, String status, LocalDateTime recordedAt) {}
    public record AttendanceResult(long attendanceId, LocalDateTime takenAt, List<AttendanceItem> items) {}
    public record AttendanceSummary(long attendanceId, long sessionId, LocalDateTime takenAt, int presentCount, int absentCount) {}
    public record ResetRequestResult(boolean ok, String message, UserRecord user) {}
    public record ResetVerifyResult(boolean ok, String code, String resetToken, String message) {}
    public record ResetPasswordResult(boolean ok, String message) {}
}
