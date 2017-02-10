package nl.vpro.poms.integration;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import nl.vpro.api.client.utils.MediaRestClientUtils;
import nl.vpro.domain.subtitles.StandaloneCue;
import nl.vpro.domain.subtitles.Subtitles;
import nl.vpro.domain.subtitles.SubtitlesUtil;
import nl.vpro.poms.AbstractApiMediaBackendTest;

import static nl.vpro.poms.Utils.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assume.assumeThat;

/**
 * @author Michiel Meeuwissen
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Slf4j
public class SubtitlesITest extends AbstractApiMediaBackendTest {

    private static final String MID_WITH_LOCATIONS = "WO_VPRO_025700";
    private static final Duration ACCEPTABLE_DURATION = Duration.ofMinutes(3);

    @Before
    public void setup() {

    }

    @Test
    public void test01addSubtitles() {
        assumeThat(backendVersionNumber, greaterThanOrEqualTo(5.1f));

        Subtitles subtitles = Subtitles.webvttTranslation(MID_WITH_LOCATIONS, Duration.ZERO, Locale.JAPANESE,
            "WEBVTT\n" +
                "\n" +
                "1\n" +
                "00:00:02.200 --> 00:00:04.150\n" +
                "" + title + "\n" +
                "\n" +
                "2\n" +
                "00:00:04.200 --> 00:00:08.060\n" +
                "*'k Heb een paar puntjes die ik met je wil bespreken\n" +
                "\n" +
                "3\n" +
                "00:00:08.110 --> 00:00:11.060\n" +
                "*Dat wil ik doen in jouw mobiele bakkerij\n" +
                "\n" +
                ""
        );
        backend.setSubtitles(subtitles);
    }


    @Test
    public void test02WaitForInFrontend() throws Exception {
        PeekingIterator<StandaloneCue> cueIterator = waitUntil(ACCEPTABLE_DURATION, () -> {
                try {
                    return Iterators.peekingIterator(
                        SubtitlesUtil.standaloneStream(MediaRestClientUtils.loadOrNull(mediaUtil.getClients().getSubtitlesRestService(),
                            MID_WITH_LOCATIONS, Locale.JAPAN)).iterator()
                    );
                } catch (IOException ioe) {
                    return null;
                }
            }
        , (pi) -> pi != null && pi.hasNext() && pi.peek().getContent().equals(title));
        assertThat(cueIterator).hasSize(3);
    }

}
