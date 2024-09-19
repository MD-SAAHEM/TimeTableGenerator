import javax.xml.transform.stream.StreamSource;
import java.sql.*;
import java.util.*;
import java.util.logging.*;
//import TimetableDisplay;
public class TimetableGenerator {
    private static final Logger logger = Logger.getLogger(TimetableGenerator.class.getName());

    private static void generateTimetable(String programId) throws SQLException {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rsCourses = null;

        try {
            // Load PostgreSQL JDBC Driver
            Class.forName("org.postgresql.Driver");
            logger.info("PostgreSQL JDBC Driver loaded successfully.");

            // Connect to PostgreSQL database
            String url = "jdbc:postgresql://localhost:5432/timetable_management";
            String user = "postgres";
            String password = "admin";
            conn = DriverManager.getConnection(url, user, password);
            stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);

            // Empty the Timetable table
            stmt.executeUpdate("DELETE FROM Timetable");
            logger.info("Timetable table cleared.");

            // Fetch courses
            String courseQuery = "SELECT * FROM CoursePrograms WHERE program_id = '" + programId + "'";
            rsCourses = stmt.executeQuery(courseQuery);
            logger.info("Courses fetched for program_id: " + programId);

            List<String> timetableSlots = defineTimetableSlots();  // Define and shuffle your slots
            Map<String, Set<String>> subjectsScheduledPerDay = new HashMap<>();
            Map<String, Integer> labSessionsPerDay = new HashMap<>();
            Map<String, Integer> periodsPerDay = new HashMap<>();
            Map<String, Integer> allocatedHours = new HashMap<>();

            // **FIRST: Allocate Hard Constraint Sessions**
            // GE and Lab sessions are allocated before regular courses.
            rsCourses.beforeFirst();  // Reset ResultSet pointer to the beginning
            while (rsCourses.next()) {
                String courseId = rsCourses.getString("course_id");
                String courseType = rsCourses.getString("course_type");
                int totalHours = rsCourses.getInt("total_hours");

                allocatedHours.put(courseId, 0);  // Initialize allocated hours

                // Allocate lab sessions
                if ("LAB".equals(courseType)) {
                    logger.info("Allocating lab sessions for course: " + courseId);
                    String[] labSlots = findAvailableLabSlot(timetableSlots, conn, programId, courseId, subjectsScheduledPerDay, labSessionsPerDay);
                    if (labSlots != null) {
                        insertTimetableEntry(conn, programId, courseId, labSlots);
                        allocatedHours.put(courseId, allocatedHours.get(courseId) + 2);
                        logger.info("Lab session allocated for course: " + courseId + " in slots: " + Arrays.toString(labSlots));
                        continue;  // Skip to the next course since Lab is allocated
                    }
                }

                // Allocate time for GE sessions only on Mondays and Wednesdays 5th and 6th periods
                if (isGeneralElective(courseId)) {
                    logger.info("Allocating GE sessions for course: " + courseId);
                    allocateGESessions(conn, programId, courseId);
                    continue;  // Skip to the next course since GE is allocated
                }

            }

            // **SECOND: Allocate Normal Sessions**
// Now, we allocate regular courses after hard constraints.
            rsCourses.beforeFirst();  // Reset ResultSet pointer to the beginning
            while (rsCourses.next()) {
                String courseId = rsCourses.getString("course_id");
                String courseType = rsCourses.getString("course_type");
                int totalHours = rsCourses.getInt("total_hours");

                // Skip already allocated GE and Lab sessions
                if (isGeneralElective(courseId) || "LAB".equals(courseType)) {
                    continue;  // GE and Labs are already handled
                }

                logger.info("Allocating regular sessions for course: " + courseId);

                // Initialize daily allocation tracking
                Map<String, Integer> allocatedHoursPerDay = new HashMap<>();
                for (String day : Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")) {
                    allocatedHoursPerDay.put(day, 0);  // Initialize allocated hours for the day
                }

                // Allocate until total hours are met
                while (allocatedHours.get(courseId) < totalHours) {
                    boolean allocated = false; // Track if any session was allocated in this loop

                    for (String day : allocatedHoursPerDay.keySet()) {
                        if (allocatedHoursPerDay.get(day) < 7) {  // Ensure that we don't exceed 7 hours
                            String slot = findAvailableSlotForDay(timetableSlots, conn, programId, courseId, subjectsScheduledPerDay, day);
                            if (slot != null) {
                                insertTimetableEntry(conn, programId, courseId, slot);
                                allocatedHours.put(courseId, allocatedHours.get(courseId) + 1);
                                allocatedHoursPerDay.put(day, allocatedHoursPerDay.get(day) + 1);  // Increment daily allocated hours
                                logger.info("Course: " + courseId + " allocated on " + day + " in slot: " + slot);
                                allocated = true; // Mark that an allocation was made
                            } else {
                                logger.warning("No available slots for course: " + courseId + " on " + day);
                            }

                            // Break if 7 hours for this day are filled
                            if (allocatedHoursPerDay.get(day) >= 7) {
                                break;
                            }
                        }
                    }

                    // Break the while loop if no sessions were allocated in this iteration
                    if (!allocated) {
                        break; // No more slots available, exit the loop
                    }
                }
            }



            logger.info("Timetable generation completed successfully.");
            System.exit(0);

        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "JDBC Driver not found", e);
            System.exit(1);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "SQL Exception occurred", e);
            System.exit(1);
        } finally {
            if (rsCourses != null) rsCourses.close();
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
            logger.info("Database connection closed.");
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
        logger.info("Timetable slots defined and shuffled.");
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

    private static String findAvailableSlotForDay(List<String> timetableSlots, Connection conn, String programId, String courseId, Map<String, Set<String>> subjectsScheduledPerDay, String day) throws SQLException {
        for (String slot : timetableSlots) {
            int period = Integer.parseInt(slot.split(":")[1]);

            // Check if the subject has already been scheduled for the day
            if (subjectsScheduledPerDay.getOrDefault(day, new HashSet<>()).contains(courseId)) {
                continue; // Skip this slot if the subject is already scheduled for the day
            }

            // Check if slot is available based on constraints
            if (isSlotAvailable(conn, day, period, programId, courseId)) {
                timetableSlots.remove(slot);
                logger.info("Available slot found: " + day + " " + period);
                return day + ":" + period;
            }
        }
        logger.warning("No available slots found for course: " + courseId + " on day: " + day);
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

            // Check for consecutive available periods
            if (isSlotAvailable(conn, day, period, programId, courseId) && isSlotAvailable(conn, day, period + 1, programId, courseId)) {
                timetableSlots.remove(day + ":" + period);
                timetableSlots.remove(day + ":" + (period + 1));
                labSessionsPerDay.put(day, labSessionsPerDay.getOrDefault(day, 0) + 1);
                logger.info("Available lab slot found: " + day + " Periods: " + period + "," + (period + 1));
                return new String[]{day + ":" + period, day + ":" + (period + 1)};
            }
        }
        return null;  // No available slots for the lab
    }

    private static boolean isGeneralElective(String courseId) {
        return courseId.startsWith("GE");
    }

    private static void allocateGESessions(Connection conn, String programId, String courseId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO Timetable (program_id, course_id, day, period) VALUES (?, ?, ?, ?)")) {
            pstmt.setString(1, programId);
            pstmt.setString(2, courseId);

            // Allocate Monday 5th and 6th period
            pstmt.setString(3, "Monday");
            pstmt.setInt(4, 5);
            pstmt.executeUpdate();
            pstmt.setString(3, "Monday");
            pstmt.setInt(4, 6);
            pstmt.executeUpdate();

            // Allocate Wednesday 5th and 6th period
            pstmt.setString(3, "Wednesday");
            pstmt.setInt(4, 5);
            pstmt.executeUpdate();
            pstmt.setString(3, "Wednesday");
            pstmt.setInt(4, 6);
            pstmt.executeUpdate();
            logger.info("GE sessions allocated for course: " + courseId);
        }
    }


    private static boolean isSlotAvailable(Connection conn, String day, int period, String programId, String courseId) throws SQLException {
        return !isSlotFilled(conn, day, period, programId);  // Modify this to add more logic for availability if required
    }

    private static void insertTimetableEntry(Connection conn, String programId, String courseId, String... slot) throws SQLException {
        for (String slots : slot) {
            String[] parts = slots.split(":");
            String day = parts[0];
            int period = Integer.parseInt(parts[1]);

            String insertSQL = "INSERT INTO Timetable (program_id, course_id, day, period) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                pstmt.setString(1, programId);
                pstmt.setString(2, courseId);
                pstmt.setString(3, day);
                pstmt.setInt(4, period);
                pstmt.executeUpdate();
                logger.info("Timetable entry inserted: " + programId + ", " + courseId + ", " + day + ", " + period);
            }
        }
    }

    public static void main(String[] args) {
        String programId = "MSCS";  // Example program ID
        try {
            generateTimetable(programId);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error generating timetable", e);
        }
        System.out.println("hii");

        try {
            TimetableDisplay.displayTimetable();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error Displaying timetable", e);
        }

    }
}