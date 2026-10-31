package sample;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/** Run the Usage002 XML documentation examples and their shared fixture in the ordinary test suite. */
@RunWith(Suite.class)
@SuiteClasses({
    Usage002_01_SimpleXmlTag.class,
    Usage002_02_SimpleXmlTagOpenAndClose.class,
    Usage002_03_SimpleXmlTagPaired.class,
    Usage002_04_SimpleXmlTagPaired_alternative.class,
    XmlTagSampleContract.class,
})
public class XmlTagSampleTest {
}
