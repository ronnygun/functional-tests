package nl.vpro.poms.npoapi;

import java.io.IOException;
import java.util.Collection;

import javax.ws.rs.core.MediaType;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import nl.vpro.domain.api.page.PageForm;
import nl.vpro.domain.api.page.PageSearchResult;
import nl.vpro.poms.ApiSearchTestHelper;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public class ApiPageSearchTest extends AbstractSearchTest<PageForm, PageSearchResult> {

    {
        addTester("NPA-331.json/woord", sr -> {
            assertThat(sr.getItems()).isNotEmpty();
            }
        );
        addAssumer("regexp.json/.*", minVersion(4.806));
        addAssumer("tags.json/.*", minVersion(4.806));

    }

    public ApiPageSearchTest(String name, PageForm form, String profile, MediaType mediaType) {
        super(name, form, profile, mediaType);
    }


    @Parameterized.Parameters
    public static Collection<Object[]> getForms() throws IOException {
        return ApiSearchTestHelper.getForms("/examples/pages/", PageForm.class, null, "vpro", "woord");
    }

    @Test
    public void search() throws Exception {
        System.out.println("--------------------" + name);
        PageSearchResult searchResultItems = clients.getPageService().find(form, profile, "", 0L, 10);
        assumeTrue(tester.apply(searchResultItems));
        test(name, searchResultItems);
    }
}
