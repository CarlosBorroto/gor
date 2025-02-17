package org.gorpipe.s3.driver;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.gorpipe.gor.driver.providers.stream.sources.CommonStreamTests;
import org.gorpipe.gor.model.DriverBackedFileReader;
import org.gorpipe.test.IntegrationTests;
import org.gorpipe.utils.DriverUtils;
import gorsat.TestUtils;
import org.gorpipe.gor.driver.DataSource;
import org.gorpipe.gor.driver.meta.SourceReference;
import org.gorpipe.gor.driver.meta.SourceReferenceBuilder;
import org.gorpipe.gor.driver.meta.SourceType;
import org.gorpipe.gor.driver.providers.stream.sources.StreamSource;
import org.gorpipe.gor.driver.providers.stream.sources.wrappers.ExtendedRangeWrapper;
import org.gorpipe.gor.driver.providers.stream.sources.wrappers.RetryWrapper;
import org.junit.*;
import org.junit.contrib.java.lang.system.ProvideSystemProperty;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

//@Category(IntegrationTests.class)
public class ITestS3Source extends CommonStreamTests {

    private static String S3_KEY;
    private static String S3_SECRET;
    private static String S3_REGION = "us-west-2";

    @Rule
    public final ProvideSystemProperty myPropertyHasMyValue
            = new ProvideSystemProperty("aws.accessKeyId", S3_KEY);

    @Rule
    public final ProvideSystemProperty otherPropertyIsMissing
            = new ProvideSystemProperty("aws.secretKey", S3_SECRET);

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog();


    @BeforeClass
    static public void setUpClass() {
        Properties props = DriverUtils.getDriverProperties();
        S3_KEY = props.getProperty("S3_KEY");
        S3_SECRET = props.getProperty("S3_SECRET");
    }


    @Override
    protected String getDataName(String name) {
        return "s3://nextcode-unittest/csa_test_data/data_sets/gor_driver_testfiles/" + name;
    }

    @Override
    protected StreamSource createSource(String name) throws IOException {
        return new S3Source(newClient(),
                new SourceReference(name));
    }

    private AmazonS3 newClient() {
        return AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(S3_KEY, S3_SECRET)))
                .withRegion(S3_REGION)
                .build();
    }

    @Override
    protected String expectCanonical(StreamSource source, String name) {
        return name;
    }

    @Override
    protected void verifyDriverDataSource(String name, DataSource fs) {
        Assert.assertEquals(ExtendedRangeWrapper.class, fs.getClass());
        fs = ((ExtendedRangeWrapper) fs).getWrapped();
        Assert.assertEquals(RetryWrapper.class, fs.getClass());
        fs = ((RetryWrapper) fs).getWrapped();
        Assert.assertEquals(S3Source.class, fs.getClass());
    }

    @Override
    protected SourceType expectedSourcetype(StreamSource fs) {
        return S3SourceType.S3;
    }

    @Ignore("Fails on linux")
    @Test
    public void testS3SecurityContext() throws IOException {
        Path p = Paths.get("genes.gord");
        try {
            Files.write(p, (getDataName("dummy.gor")+"\tstuff").getBytes());
            String securityContext = DriverUtils.awsSecurityContext(S3_KEY, S3_SECRET);
            String res = TestUtils.runGorPipe("create xxx = gor -f 'stuff' genes.gord | top 10; gor [xxx]", true, securityContext);
            Assert.assertEquals("Wrong result from s3 dictionary", "chrom\tpos\ta\tSource\n" +
                    "chr1\t0\tb\tstuff\n", res);
        } finally {
            if(Files.exists(p)) Files.delete(p);
        }
    }

    @Test
    public void testS3Write() {
        TestUtils.runGorPipe("gor ../tests/data/gor/genes.gor | top 1 | write s3://nextcode-unittest/s3write/genes.gor");
    }

    @Ignore("Local file, also too large and slow to use always, no clean up")
    @Test
    public void testS3WriteLargeFile() {
        String localPath = "../../testing/data/ref/hg19/dbsnp.gorz";
        long startTime = System.currentTimeMillis();
        TestUtils.runGorPipe(String.format("gor %s | top 100000000 | write s3://nextcode-unittest/s3write/large.gorz", localPath));
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Time: " + duration + "ms");
    }

    //@Ignore("Too slow to always run")
    @Test
    public void testS3WritePgorGord() throws IOException {
        String randomId = UUID.randomUUID().toString();
        String dict = String.format("s3://nextcode-unittest/s3write/%s-genes.gord", randomId);
        TestUtils.runGorPipe("pgor -split 2 ../tests/data/gor/genes.gor | top 2 | write " + dict);
        String expected = TestUtils.runGorPipe("pgor -split 2 ../tests/data/gor/genes.gor | top 2");
        String result = TestUtils.runGorPipe("gor " + dict);
        Assert.assertEquals(expected, result);
        DriverBackedFileReader fileReader = new DriverBackedFileReader("");
        fileReader.deleteDirectory(dict);
    }

    @Test
    public void testS3WriteServerMode() throws IOException {
        String securityContext = DriverUtils.awsSecurityContext(S3_KEY, S3_SECRET);
        TestUtils.runGorPipe("gor ../tests/data/gor/genes.gor | top 1 | write s3://nextcode-unittest/s3write/genes.gor",
                true, securityContext, new String[] {"s3://"});
    }

    @Test
    public void testS3NotAllBytesEx() throws IOException {
        String securityContext = DriverUtils.awsSecurityContext(S3_KEY, S3_SECRET);
        TestUtils.runGorPipe("gor  s3://nextcode-unittest/csa_test_data/data_sets/ref/versions/hg19/dbsnp.gorz -p chr2 | top 1000000 | group genome -count",
                true, securityContext, null);
        Assert.assertFalse(systemErrRule.getLog().contains("Not all bytes were read from the S3ObjectInputStream"));
    }

    @Test
    public void testS3Meta() throws IOException {
        String securityContext = DriverUtils.awsSecurityContext(S3_KEY, S3_SECRET);
        var result = TestUtils.runGorPipe("meta s3://nextcode-unittest/s3write/genes.gor", true, securityContext, new String[] {"s3://"});

        Assert.assertFalse(result.isEmpty());
        Assert.assertTrue(result.contains("SOURCE\tTYPE\tS3"));
    }

    @Test
    public void testS3MetaWithMetafile() throws IOException {
        String securityContext = DriverUtils.awsSecurityContext(S3_KEY, S3_SECRET);
        TestUtils.runGorPipe("gor ../tests/data/gor/genes.gor | top 1 | write s3://nextcode-unittest/s3write/genes.gorz", true, securityContext, new String[] {"s3://"});
        var result = TestUtils.runGorPipe("meta s3://nextcode-unittest/s3write/genes.gorz", true, securityContext, new String[] {"s3://"});

        Assert.assertFalse(result.isEmpty());
        Assert.assertTrue(result.contains("SOURCE\tTYPE\tS3"));
        Assert.assertTrue(result.contains("GOR\tMD5"));
        Assert.assertTrue(result.contains("GOR\tLINE_COUNT"));
    }

    @Override
    protected SourceReference mkSourceReference(String name) throws IOException {
        return new SourceReferenceBuilder(name).securityContext(DriverUtils.awsSecurityContext(S3_KEY, S3_SECRET)).build();
    }

    @Override
    protected long expectedTimeStamp(String s) {
        return newClient().getObjectMetadata("nextcode-unittest", "csa_test_data/data_sets/gor_driver_testfiles/" + s).getLastModified().getTime();
    }
}
