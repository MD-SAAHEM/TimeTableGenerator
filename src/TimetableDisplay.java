import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

public class TimetableDisplay {

    public static void displayTimetable() throws SQLException {
        System.out.println("Displaying timetable...");
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            // Connect to PostgreSQL database
            conn = DatabaseUtil.connect();
            stmt = conn.createStatement();

            // Fetch timetable for MCAB ordered by day and period
            String query = "SELECT * FROM Timetable WHERE program_id = 'MSCS' ORDER BY day, period";
            rs = stmt.executeQuery(query);

            String currentDay = "";
            Set<Integer> displayedPeriods = new HashSet<>();
            System.out.printf("%-10s %-10s %-10s %-10s %-10s%n", "Day", "Period", "Course", "Faculty", "Classroom");
            System.out.println("-------------------------------------------------------------");

            while (rs.next()) {
                String day = rs.getString("day");
                int period = rs.getInt("period");
                String courseId = rs.getString("course_id");
                String facultyId = rs.getString("faculty_id");
                String classroomId = rs.getString("classroom_id");

                if (!day.equals(currentDay)) {
                    currentDay = day;
                    displayedPeriods.clear();
                }

                if (!displayedPeriods.contains(period)) {
                    displayedPeriods.add(period);
                    System.out.printf("%-10s %-10d %-10s %-10s %-10s%n", day, period, courseId, facultyId, classroomId);
                }
            }
        } finally {
            if (rs != null) rs.close();
            if (stmt != null) stmt.close();
            if (conn != null) conn.close();
        }
    }

    public static void main(String[] args) {
        try {
            displayTimetable();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}