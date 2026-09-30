SMART CLASSROOM V3.9 - JAVA 17 - MODERN UI
==============================

CHAY LAN DAU:
1) Bat MySQL Server.
2) Chay SETUP_LIBS.bat (can Internet lan dau de tai MySQL Connector + WebRTC Native).
3) Chay BUILD.bat.
4) Chay START_SERVER.bat.
5) Tren Server nhap MySQL va bam KHOI DONG SERVER.
6) Chay START_CLIENT.bat.

QUEN MAT KHAU - EMAIL OTP:
- Server V3.5 co them 2 o: Gmail gui OTP + Google App Password.
- KHONG dung mat khau Gmail thong thuong. Can Google App Password (tai khoan Google bat xac minh 2 buoc).
- Khi Server khoi dong, neu cau hinh email day du se hien: OTP Email: SAN SANG.
- Client: Dang nhap -> Quen mat khau? -> nhap email -> nhan ma 6 so -> xac minh -> doi mat khau.
- OTP co hieu luc 5 phut, toi da 5 lan nhap sai. Sau khi xac minh, phien doi mat khau co hieu luc 10 phut.
- Moi email chi co the yeu cau gui lai sau 60 giay.
- OTP duoc luu trong MySQL o dang SHA-256, khong luu ma OTP ro.
- Mat khau moi duoc bam PBKDF2 nhu mat khau dang ky.

DATABASE:
- Mac dinh: smart_classroom
- Server tu tao database/table neu tai khoan MySQL co quyen.
- V3.5 tu tao them bang password_reset_otps, khong can tu CREATE TABLE thu cong.

PORT:
- TCP 5001: dang ky, dang nhap, quen mat khau, quan ly lop, du lieu quan trong.
- UDP 5000: heartbeat, session, chat, diem danh, signaling WebRTC.

TEST 2 MAY:
- May Server: MySQL + START_SERVER.bat.
- May Client khac: chi can JDK + project Client; nhap IP LAN cua may Server.
- May Client KHONG ket noi truc tiep MySQL.


TINH NANG V3.5
- Chia se man hinh native WebRTC ngay trong Java Desktop, khong mo browser/HTML.
- Trong phong hoc, bam "Chia se man hinh" -> chon monitor -> video outgoing doi tu camera sang desktop; mic van hoat dong.
- Bam "Dung chia se" de quay lai camera.
- Gui tep ngay trong tab Tro chuyen bang nut "Tep".
- Tep toi da 20 MB, chia chunk 8 KB qua UDP, co ACK + retry va yeu cau lai chunk bi thieu.
- Tep nhan duoc luu tai Downloads\SmartClassroomReceived.

LUU Y SCREEN SHARE
- Giao vien co PeerConnection toi moi sinh vien nen khi giao vien share, tat ca sinh vien trong buoi hoc se thay man hinh.
- Sinh vien share se truyen tren cac PeerConnection hien co; voi mo hinh teacher-centered hien tai, giao vien se thay man hinh cua sinh vien do.


=== V3.6 - QUEN MAT KHAU: EMAIL CLIENT LA NGUOI NHAN ===
- Client bam Quen mat khau va nhap email da dang ky tai khoan.
- Server tim dung user trong MySQL, sau do lay recipientEmail tu chinh ban ghi users.
- OTP 6 so duoc gui TOI email cua Client do.
- Gmail tren man hinh Server chi la tai khoan HE THONG DE GUI mail (FROM), khong phai email nhan OTP.
- OTP dung -> Client duoc chuyen sang form dat mat khau moi.
- OTP het han sau 5 phut, gioi han so lan nhap sai va co cooldown gui lai.


=== V3.8 - MAN HINH SHARE CHIEM UU TIEN ===
- Khi co Client bat dau chia se man hinh, tile cua nguoi share duoc phong lon chiem vung video chinh.
- Camera cua cac Client khac KHONG BI XOA va KHONG NGAT WEBRTC; chi duoc thu nho thanh thumbnail ben duoi.
- Nut An camera nho / Hien camera nho chi an/hien giao dien thumbnail, cac luong camera van ton tai.
- Khi dung share hoac nguoi share roi phong, giao dien tu tro ve luoi camera binh thuong.
- Neu Client khac bat dau share, focus tu dong chuyen sang man hinh dang share moi.


=== V3.9 - MODERN UI ===
- Giao dien Client/Server duoc thiet ke lai dong bo, hien dai, bo goc va de nhin.
- Meeting dark workspace, lobby/dashboard sang, controls va bang du lieu dong nhat.
- Khong doi protocol hay database; giu nguyen toan bo chuc nang V3.8.
