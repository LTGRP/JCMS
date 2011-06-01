/*
   Copyright (C) 2002 MySQL AB

      This program is free software; you can redistribute it and/or modify
      it under the terms of the GNU General Public License as published by
      the Free Software Foundation; either version 2 of the License, or
      (at your option) any later version.

      This program is distributed in the hope that it will be useful,
      but WITHOUT ANY WARRANTY; without even the implied warranty of
      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
      GNU General Public License for more details.

      You should have received a copy of the GNU General Public License
      along with this program; if not, write to the Free Software
      Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

 */
package testsuite.regression;

import testsuite.BaseTestCase;

import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

import java.sql.ResultSet;
import java.sql.Statement;


/**
 * Regression tests for the Statement class
 *
 * @author Mark Matthews
 */
public class StatementRegressionTest extends BaseTestCase {
    /**
     * Constructor for StatementRegressionTest.
     *
     * @param name the name of the test to run
     */
    public StatementRegressionTest(String name) {
        super(name);
    }

    /**
     * Tests that you can close a statement twice without an NPE.
     *
     * @throws Exception if an error occurs.
     */
    public void testCloseTwice() throws Exception {
        Statement closeMe = this.conn.createStatement();
        closeMe.close();
        closeMe.close();
    }

    /**
     * Tests that 'LOAD DATA LOCAL INFILE' works
     *
     * @throws Exception if any errors occur
     */
    public void testLoadData() throws Exception {
        try {
            int maxAllowedPacket = 1048576;

            //stmt.executeUpdate("SET VARIABLE max_allowed_packet=" + maxAllowedPacket);
            stmt.executeUpdate("DROP TABLE IF EXISTS loadDataRegress");
            stmt.executeUpdate(
                "CREATE TABLE loadDataRegress (field1 int, field2 int)");

            File tempFile = File.createTempFile("mysql", "txt");
            tempFile.deleteOnExit();

            Writer out = new FileWriter(tempFile);

            int count = 0;
            int rowCount = maxAllowedPacket * 4;

            for (int i = 0; i < rowCount; i++) {
                out.write((count++) + "\t" + (count++) + "\n");
            }

            out.close();

            StringBuffer fileNameBuf = null;

            if (File.separatorChar == '\\') {
                fileNameBuf = new StringBuffer();

                String fileName = tempFile.getAbsolutePath();
                int fileNameLength = fileName.length();

                for (int i = 0; i < fileNameLength; i++) {
                    char c = fileName.charAt(i);

                    if (c == '\\') {
                        fileNameBuf.append("/");
                    } else {
                        fileNameBuf.append(c);
                    }
                }
            } else {
                fileNameBuf = new StringBuffer(tempFile.getAbsolutePath());
            }

            int updateCount = stmt.executeUpdate("LOAD DATA LOCAL INFILE '"
                    + fileNameBuf.toString() + "' INTO TABLE loadDataRegress");
            assertTrue(updateCount == rowCount);
        } finally {
            stmt.executeUpdate("DROP TABLE IF EXISTS loadDataRegress");
        }
    }

    /**
     * Tests PreparedStatement.setCharacterStream() to ensure it accepts > 4K
     * streams
     *
     * @throws Exception if an error occurs.
     */
    public void testSetCharacterStream() throws Exception {
        try {
            stmt.executeUpdate("DROP TABLE IF EXISTS charStreamRegressTest");
            stmt.executeUpdate(
                "CREATE TABLE charStreamRegressTest(field1 text)");

            pstmt = conn.prepareStatement(
                    "INSERT INTO charStreamRegressTest VALUES (?)");

            char[] charBuf = new char[16384];

            for (int i = 0; i < charBuf.length; i++) {
                charBuf[i] = 'A';
            }

            CharArrayReader reader = new CharArrayReader(charBuf);

            pstmt.setCharacterStream(1, reader, charBuf.length);
            pstmt.executeUpdate();

            rs = stmt.executeQuery("SELECT field1 FROM charStreamRegressTest");

            rs.next();

            String result = rs.getString(1);

            assertTrue(result.length() == charBuf.length);

            stmt.execute("TRUNCATE TABLE charStreamRegressTest");

            // Test that EOF is not thrown
            reader = new CharArrayReader(charBuf);
            pstmt.setCharacterStream(1, reader, (charBuf.length * 2));
            pstmt.executeUpdate();

            rs = stmt.executeQuery("SELECT field1 FROM charStreamRegressTest");

            rs.next();

            result = rs.getString(1);

            assertTrue("Retrieved value of length " + result.length()
                + " != length of inserted value " + charBuf.length,
                result.length() == charBuf.length);

            // Test single quotes inside identifers
            stmt.executeUpdate("DROP TABLE IF EXISTS `charStream'RegressTest`");
            stmt.executeUpdate(
                "CREATE TABLE `charStream'RegressTest`(field1 text)");

            pstmt = conn.prepareStatement(
                    "INSERT INTO `charStream'RegressTest` VALUES (?)");

            reader = new CharArrayReader(charBuf);
            pstmt.setCharacterStream(1, reader, (charBuf.length * 2));
            pstmt.executeUpdate();

            rs = stmt.executeQuery(
                    "SELECT field1 FROM `charStream'RegressTest`");

            rs.next();

            result = rs.getString(1);

            assertTrue("Retrieved value of length " + result.length()
                + " != length of inserted value " + charBuf.length,
                result.length() == charBuf.length);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (Exception ex) {
                    // ignore
                }

                rs = null;
            }

            stmt.executeUpdate("DROP TABLE IF EXISTS `charStream'RegressTest`");
            stmt.executeUpdate("DROP TABLE IF EXISTS charStreamRegressTest");
        }
    }

    /**
     * Tests a bug where Statement.setFetchSize() does not work for values
     * other than 0 or Integer.MIN_VALUE
     *
     * @throws Exception if any errors occur
     */
    public void testSetFetchSize() throws Exception {
        int oldFetchSize = stmt.getFetchSize();

        try {
            stmt.setFetchSize(10);
        } finally {
            stmt.setFetchSize(oldFetchSize);
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @throws Exception DOCUMENT ME!
     */
    public void testUpdatableStream() throws Exception {
        try {
            this.stmt.executeUpdate("DROP TABLE IF EXISTS updateStreamTest");
            this.stmt.executeUpdate(
                "CREATE TABLE updateStreamTest (keyField INT NOT NULL AUTO_INCREMENT PRIMARY KEY, field1 BLOB)");

            int streamLength = 16385;
            byte[] streamData = new byte[streamLength];

            /* create an updatable statement */
            Statement updStmt = this.conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_UPDATABLE);

            /* fill the resultset with some values */
            ResultSet updRs = updStmt.executeQuery("SELECT * FROM updateStreamTest");

            /* move to insertRow */
            updRs.moveToInsertRow();

            /* update the table */
            updRs.updateBinaryStream("field1",
                new ByteArrayInputStream(streamData), streamLength);

            updRs.insertRow();
        } finally {
            this.stmt.executeUpdate("DROP TABLE IF EXISTS updateStreamTest");
        }
    }
}
