import java.sql.*;

/** Test fixture: adds a historical UUID for an existing username to a stopped local server. */
class InsertDuplicate {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+args[0]);
            PreparedStatement insert=db.prepareStatement("INSERT INTO players (uuid,name,play_ms,manual_rank,og_earned,playtime_imported,sidebar_hidden) VALUES (?, ?, 0, 'owner', 0, 1, 0)")) {
            insert.setString(1,args[1]);insert.setString(2,args[2]);insert.executeUpdate();
        }
    }
}
