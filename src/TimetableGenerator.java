import java.sql.*;
import java.util.*;

public class TimetableGenerator {

    private static void generateTimetable(String programId) throws SQLException {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rsCourses = null;

        try {
            // Load PostgreSQL JDBC Driver
            Class.forName("org.postgresql.Driver");

            // Connect to PostgreSQL database
            String url = "jdbc:postgresql://localhost:5432/timetable_management";
            String user = "postgres";
            String password = "admin";
            conn = DriverManager.getConnection(url, user, password);
            stmt = conn.createStatement();

            // Empty the Timetable table
            stmt.executeUpdate("DELETE FROM Timetable");

            // Fetch courses
            String courseQuery = "SELECT * FROM CoursePrograms WHERE program_id = '" + programId + "'";
            rsCourses = stmt.executeQuery(courseQuery);

            List<String> timetableSlots = defineTimetableSlots();  // Define and shuffle your slots
            Map<String, Set<String>> subjectsScheduledPerDay = new HashMap<>();
            Map<String, Integer> labSessionsPerDay = new HashMap<>();
            Map<String, Integer> periodsPerDay = new HashMap<>();

            while (rsCourses.next()) {
                String courseId = rsCourses.getString("course_id");
                String courseType = rsCourses.getString("course_type");
                int totalHours = rsCourses.getInt("total_hours");

                // Allocate time for GE sessions only on Mondays and Wednesdays 5th and 6th periods
                if (isGeneralElective(courseId)) {
                    allocateGESessions(conn, programId, courseId);
                    continue;
                }

                // Allocate lab sessions
                if ("LAB".equals(courseType)) {
                    String[] labSlots = findAvailableLabSlot(timetableSlots, conn, programId, courseId, subjectsScheduledPerDay, labSessionsPerDay);
                    if (labSlots != null) {
                        insertTimetableEntry(conn, programId, courseId, labSlots);
                        continue;
                    }
                }

                // Allocate regular courses
                for (int i = 0; i < totalHours; i++) {
                    String slot = findAvailableSlot(timetableSlots, conn, programId, courseId, subjectsScheduledPerDay);
                    if (slot != null) {
                        insertTimetableEntry(conn, programId, courseId, slot);
                    }
                }
            }

            // Ensure each day has exactly 7 periods
            for (String day : new String[]{"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"}) {
                int periods = periodsPerDay.getOrDefault(day, 0);
                while (periods < 7) {
                    String slot = findAvailableSlot(timetableSlots, conn, programId, "APT", subjectsScheduledPerDay);
                    if (slot != null) {
                        insertTimetableEntry(conn, programId, "APT", slot);
                        periods++;
                    }
                }
            }

            // Allocate additional sessions (LIB, MENT, APT) once per week
            scheduleAdditionalSessions(programId, conn);

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (rsCourses != null) rsCourses.close();
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
        }
    }

    private static List<String> defineTimetableSlots() {
        List<String> slots = new ArrayList<>();
        // Define slots for each day and period (1 to 7)
        for (String day : Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")) {
            for (int period = 1; period <= 7; period++) {
                slots.add(day + ":" + period);
            }
        }
        // Shuffle the slots to ensure different allocations on each generation
        Collections.shuffle(slots);
        return slots;
    }

    private static boolean isSlotFilled(Connection conn, String day, int period, String programId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM Timetable WHERE day = ? AND period = ? AND program_id = ?")) {
            pstmt.setString(1, day);
            pstmt.setInt(2, period);
            pstmt.setString(3, programId);
            ResultSet rs = pstmt.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static String findAvailableSlot(List<String> timetableSlots, Connection conn, String programId, String courseId, Map<String, Set<String>> subjectsScheduledPerDay) throws SQLException {
        for (String slot : timetableSlots) {
            String day = slot.split(":")[0];
            int period = Integer.parseInt(slot.split(":")[1]);

            // Check if the subject has already been scheduled for the day
            if (subjectsScheduledPerDay.getOrDefault(day, new HashSet<>()).contains(courseId)) {
                continue; // Skip this slot if the subject is already scheduled for the day
            }

            // Check if slot is available based on constraints
            if (isSlotAvailable(conn, day, period, programId, courseId)) {
                timetableSlots.remove(slot);
                return slot;
            }
        }
        return null;
    }

    private static String[] findAvailableLabSlot(List<String> timetableSlots, Connection conn, String programId, String courseId, Map<String, Set<String>> subjectsScheduledPerDay, Map<String, Integer> labSessionsPerDay) throws SQLException {
        for (String slot : timetableSlots) {
            String day = slot.split(":")[0];
            int period = Integer.parseInt(slot.split(":")[1]);

            // Check if the subject has already been scheduled for the day
            if (subjectsScheduledPerDay.getOrDefault(day, new HashSet<>()).contains(courseId)) {
                continue; // Skip this slot if the subject is already scheduled for the day
            }

            // Check if more than one lab session is already scheduled for the day
            if (labSessionsPerDay.getOrDefault(day, 0) >= 1) {
                continue; // Skip this slot if more than one lab session is already scheduled for the day
            }

            // Check if two continuous slots are available within the 7-period constraint
            if (period < 7 && isSlotAvailable(conn, day, period, programId, courseId) && isSlotAvailable(conn, day, period + 1, programId, courseId)) {
                timetableSlots.remove(slot);
                timetableSlots.remove(day + ":" + (period + 1));
                labSessionsPerDay.put(day, labSessionsPerDay.getOrDefault(day, 0) + 1);
                return new String[]{slot, day + ":" + (period + 1)};
            }
        }
        return null;
    }

    private static boolean isSlotAvailable(Connection conn, String day, int period, String programId, String courseId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM Timetable WHERE day = ? AND period = ? AND program_id = ?")) {
            pstmt.setString(1, day);
            pstmt.setInt(2, period);
            pstmt.setString(3, programId);
            ResultSet rs = pstmt.executeQuery();
            rs.next();
            return rs.getInt(1) == 0;
        }
    }

    private static void insertTimetableEntry(Connection conn, String programId, String courseId, String... slots) throws SQLException {
        for (String slot : slots) {
            String day = slot.split(":")[0];
            int period = Integer.parseInt(slot.split(":")[1]);
            String facultyId = assignFaculty(conn, day, period);
            String classroomId = assignClassroom(conn);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO Timetable (program_id, day, period, course_id, faculty_id, classroom_id) VALUES (?, ?, ?, ?, ?, ?)")) {
                pstmt.setString(1, programId);
                pstmt.setString(2, day);
                pstmt.setInt(3, period);
                pstmt.setString(4, courseId);
                pstmt.setString(5, facultyId);
                pstmt.setString(6, classroomId);
                pstmt.executeUpdate();
            }
        }
    }

    private static String assignFaculty(Connection conn, String day, int period) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT faculty_id FROM Faculty WHERE faculty_id NOT IN " +
                        "(SELECT faculty_id FROM Timetable WHERE day = ? AND period = ?) LIMIT 1")) {
            pstmt.setString(1, day);
            pstmt.setInt(2, period);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("faculty_id");
            }
            throw new RuntimeException("No faculty available for " + day + " period " + period);
        }
    }

    private static String assignClassroom(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT classroom_id FROM Classrooms LIMIT 1")) {
            if (rs.next()) {
                return rs.getString("classroom_id");
            }
            throw new RuntimeException("No classroom available.");
        }
    }

    private static void scheduleAdditionalSessions(String programId, Connection conn) throws SQLException {
        List<String> additionalSessions = Arrays.asList("LIB", "MENT", "APT");
        for (String sessionId : additionalSessions) {
            // Check if the session_id already exists
            try (PreparedStatement checkStmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM AdditionalSessions WHERE session_id = ?")) {
                checkStmt.setString(1, sessionId);
                ResultSet rs = checkStmt.executeQuery();
                rs.next();
                if (rs.getInt(1) == 0) {
                    // Insert the additional session
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO AdditionalSessions (session_id, session_name) VALUES (?, ?)")) {
                        insertStmt.setString(1, sessionId);
                        insertStmt.setString(2, getSessionName(sessionId));
                        insertStmt.executeUpdate();
                    }
                }
            }
        }
    }

    private static String getSessionName(String sessionId) {
        switch (sessionId) {
            case "LIB":
                return "Library";
            case "MENT":
                return "Mentoring";
            case "APT":
                return "Aptitude Training";
            default:
                return "Unknown";
        }
    }

    private static boolean isGeneralElective(String courseId) {
        // Assuming course IDs for General Electives are predefined or marked
        return courseId.startsWith("GE");
    }

    private static void allocateGESessions(Connection conn, String programId, String courseId) throws SQLException {
        String[] geDays = {"Monday", "Wednesday"};
        int[] gePeriods = {5, 6};

        for (String day : geDays) {
            for (int period : gePeriods) {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO Timetable (program_id, day, period, course_id, faculty_id, classroom_id) VALUES (?, ?, ?, ?, ?, ?)")) {
                    pstmt.setString(1, programId);
                    pstmt.setString(2, day);
                    pstmt.setInt(3, period);
                    pstmt.setString(4, courseId);
                    pstmt.setString(5, "GE_FACULTY");
                    pstmt.setString(6, "M400");
                    pstmt.executeUpdate();
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            generateTimetable("MCAB");  // Example program ID
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}