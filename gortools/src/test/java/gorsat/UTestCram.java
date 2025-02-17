/*
 *  BEGIN_COPYRIGHT
 *
 *  Copyright (C) 2011-2013 deCODE genetics Inc.
 *  Copyright (C) 2013-2019 WuXi NextCode Inc.
 *  All Rights Reserved.
 *
 *  GORpipe is free software: you can redistribute it and/or modify
 *  it under the terms of the AFFERO GNU General Public License as published by
 *  the Free Software Foundation.
 *
 *  GORpipe is distributed "AS-IS" AND WITHOUT ANY WARRANTY OF ANY KIND,
 *  INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY,
 *  NON-INFRINGEMENT, OR FITNESS FOR A PARTICULAR PURPOSE. See
 *  the AFFERO GNU General Public License for the complete license terms.
 *
 *  You should have received a copy of the AFFERO GNU General Public License
 *  along with GORpipe.  If not, see <http://www.gnu.org/licenses/agpl-3.0.html>
 *
 *  END_COPYRIGHT
 */

package gorsat;

import org.apache.commons.io.FileUtils;
import org.gorpipe.exceptions.GorResourceException;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.driver.meta.DataType;
import org.gorpipe.gor.util.DataUtil;
import org.gorpipe.test.utils.FileTestUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;

public class UTestCram {

    protected static File bamLinkFile;

    @Rule
    public TemporaryFolder workDir = new TemporaryFolder();

    public static File createWrongConfigFile(File directory) throws IOException {
        return FileTestUtils.createTempFile(directory, "generic.gor",
                "buildPath\t../tests/data/ref_mini/chromSeq\n" +
                        "buildSizeFile\t../tests/data/ref_mini/buildsize.gor\n" +
                        "buildSplitFile\t../tests/data/ref_mini/buildsplit.txt\n" +
                        "cramReferencePath\t../tests/data/external/samtools/cram_query_sorted2.fasta"
        );
    }

    @Test
    public void readCramWithFastaReference() {
        String[] lines = TestUtils.runGorPipeLines("gor ../tests/data/external/samtools/cram_query_sorted.cram -ref ../tests/data/external/samtools/cram_query_sorted.fasta");
        Assert.assertEquals(8, lines.length);
    }

    @Test
    public void readCramFromBamLinkWithFastaReference() throws IOException {
        bamLinkFile = FileTestUtils.createTempFile(workDir.getRoot(),
                DataUtil.toLinkFile("cram_query_sorted", DataType.BAM),
                Paths.get(
                        DataUtil.toFile( "../tests/data/external/samtools/cram_query_sorted", DataType.CRAM)
                ).toAbsolutePath().toString()
        );
        String[] lines = TestUtils.runGorPipeLines("gor " +
                bamLinkFile.getCanonicalPath() +
                " -ref " +
                DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.FASTA));
        Assert.assertEquals(8, lines.length);
    }


    @Test
    public void readCramWithFastaReferenceFromProperty() {
        try {
            System.setProperty(
                    "gor.driver.cram.fastareferencesource",
                    DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.FASTA)
            );
            String[] lines = TestUtils.runGorPipeLines("gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM));
            Assert.assertEquals(8, lines.length);
        } finally {
            System.clearProperty("gor.driver.cram.fastareferencesource");
        }
    }

    @Test
    public void readCramWithFastaReferenceFromConfig() {
        System.clearProperty("gor.driver.cram.fastareferencesource");
        String[] args = new String[]{
                "gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM),
                "-config",
                DataUtil.toFile("../tests/config/gor_unittests_config", DataType.TXT)
        };
        int count = TestUtils.runGorPipeCount(args);
        Assert.assertEquals(7, count);
    }

    @Test
    public void readCramWithFastaReferenceFromConfigException() throws IOException {
        File wrongConfigFile = createWrongConfigFile(workDir.getRoot());
        System.clearProperty("gor.driver.cram.fastareferencesource");
        String[] args = new String[]{
                "gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM),
                "-config",
                wrongConfigFile.getCanonicalPath()};
        try {
            TestUtils.runGorPipeCount(args);
        } catch (GorResourceException e) {
            Assert.assertEquals("Reference does not exist.", e.getMessage());
            Assert.assertTrue(e.getUri().endsWith("cram_query_sorted2.fasta"));
        }
    }

    @Test
    public void readCramWithFastaReferenceAndGenerateMissingAttributes() {
        try {
            System.setProperty("gor.driver.cram.fastareferencesource", DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.FASTA));
            System.setProperty("gor.driver.cram.generatemissingattributes", "false");
            String[] linesWithoutMissingAttributes = TestUtils.runGorPipeLines("gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM));
            System.setProperty("gor.driver.cram.generatemissingattributes", "true");
            String[] linesWithMissingAttributes = TestUtils.runGorPipeLines("gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM));

            Assert.assertEquals(8, linesWithoutMissingAttributes.length);
            Assert.assertEquals(8, linesWithMissingAttributes.length);
            // See if we have the missing entry in the last column.
            Assert.assertFalse(linesWithoutMissingAttributes[1].contains("NM="));
            Assert.assertTrue(linesWithMissingAttributes[1].contains("NM="));

        } finally {
            System.clearProperty("gor.driver.cram.fastareferencesource");
            System.clearProperty("gor.driver.cram.generatemissingattributes");
        }

    }

    @Test(expected = GorResourceException.class)
    public void readCramWithNoReference() {
        TestUtils.runGorPipeLines("gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM));
    }

    @Test(expected = GorResourceException.class)
    public void readCramWithReferenceFileButFileNotFound() {
        TestUtils.runGorPipeLines("gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM) + " -ref " + DataUtil.toFile("/foo/bar", DataType.FASTA));
    }

    @Test(expected = GorSystemException.class)
    public void readCramWithIncompatibleFastaReference() {
        TestUtils.runGorPipeLines("gor " + DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.CRAM) + " -ref " + DataUtil.toFile( "../tests/data/external/samtools/ce", DataType.FA));
    }

    @Test
    public void readCramWithFastaReferenceInRefFile() throws IOException {

        String basePath = "../tests/data/external/samtools";
        String fileName = DataUtil.toFile("cram_query_sorted", DataType.CRAM);
        String[] filesToCopy = {fileName, DataUtil.toFile(fileName, DataType.CRAI)};
        copyFiles(basePath, workDir.getRoot().toString(), filesToCopy);

        // Create ref file for the cramFile
        File referenceFile = workDir.newFile("cram_query_sorted.cram.ref");
        FileUtils.writeStringToFile(referenceFile, DataUtil.toFile("../tests/data/external/samtools/cram_query_sorted", DataType.FASTA), Charset.defaultCharset());

        String[] lines = TestUtils.runGorPipeLines(String.format("gor %1$s/" + fileName, workDir.getRoot()));
        Assert.assertEquals(8, lines.length);
    }

    private void copyFiles(String baseDirectory, String destinationDirectory, String[] filesToCopy) throws IOException {
        for (String fileName : filesToCopy) {
            File sourceFile = new File(baseDirectory, fileName);
            File destinationFile = new File(destinationDirectory, fileName);
            FileUtils.copyFile(sourceFile, destinationFile);
        }
    }

    @Test
    public void testCramMeta() {
        var result = TestUtils.runGorPipe("META ../tests/data/external/samtools/cram_query_sorted.cram");
        Assert.assertTrue(result.contains("BAM\t@"));

    }
}
