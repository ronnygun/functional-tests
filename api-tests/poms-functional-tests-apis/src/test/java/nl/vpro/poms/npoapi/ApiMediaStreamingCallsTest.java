package nl.vpro.poms.npoapi;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.InputStream;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import nl.vpro.api.client.utils.MediaRestClientUtils;
import nl.vpro.domain.api.Order;
import nl.vpro.domain.api.*;
import nl.vpro.domain.api.profile.Profile;
import nl.vpro.domain.media.MediaObject;
import nl.vpro.domain.media.Schedule;
import nl.vpro.jackson2.JsonArrayIterator;
import nl.vpro.logging.LoggerOutputStream;
import nl.vpro.logging.simple.*;
import nl.vpro.poms.AbstractApiTest;
import nl.vpro.util.*;

import static nl.vpro.api.client.utils.MediaRestClientUtils.sinceString;
import static nl.vpro.testutils.Utils.CONFIG;
import static nl.vpro.util.CloseableIterator.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Log4j2
class ApiMediaStreamingCallsTest extends AbstractApiTest {


    private Instant FROM = Instant.now().minus(Duration.ofDays(14)).truncatedTo(ChronoUnit.SECONDS);

    private static final int CHANGES_MAX = 5000;

    int couchdbSince;


    @BeforeEach
    void setup() {
        switch(CONFIG.env()) {
            case DEV:
                couchdbSince = 25387000;
                FROM = NOWI.minus(Duration.ofDays(100));
                break;
            case TEST:
                couchdbSince = 19831435;
                break;
            default:
                couchdbSince = 20794000;
                break;
        }
        mediaUtil.getClients().getMediaServiceNoTimeout();
    }


    @Test
    public void testChangesNoProfile() throws Exception {
        testChanges(null, FROM, CHANGES_MAX);
    }


    @Test
    public void testCloseChanges() throws Exception {

        testChanges(null,
            LocalDateTime.of(2017, 6, 12, 10, 32).atZone(Schedule.ZONE_ID).toInstant(), 40000);
    }

    @Test
    public void testChangesWithProfile() throws Exception {
        testChanges("vpro-predictions", FROM, CHANGES_MAX);
    }

    @Test
    public void testChangesMissingProfile() {
        assertThrows(javax.ws.rs.NotFoundException.class, () -> {
            testChanges("bestaatniet", FROM, CHANGES_MAX);
        });
    }

    @Test
    public void testIterateMissingProfile() {
        assertThrows(javax.ws.rs.NotFoundException.class, () -> testIterate("bestaatniet", CHANGES_MAX));
    }

    @Test
    public void testChangesWithOldNoProfile() throws IOException {
        testChangesWithOld(null, CHANGES_MAX);
    }

    @Test
    @Disabled("No need to support this any more")
    public void testChangesWithOldAndProfile() throws IOException {
        testChangesWithOld("vpro-predictions", CHANGES_MAX);
    }


    @Test
    @Disabled("No need to support this any more")
    public void testChangesOldMissingProfile() {
        assertThrows(javax.ws.rs.NotFoundException.class, () ->
            testChangesWithOld("bestaatniet", CHANGES_MAX)
        );
    }


    private static Stream<Arguments> changesFeedParameters() {
        final Instant JAN2017 = LocalDate.of(2017, 1, 1).atStartOfDay(Schedule.ZONE_ID).toInstant();
        final int secs2015 = (int) (LocalDate.of(2015, 1, 1).atStartOfDay(Schedule.ZONE_ID).toInstant().toEpochMilli() / 1000);
        final Instant RANDOM = Instant.ofEpochSecond(secs2015 + new Random().nextInt((int) (Instant.now().minus(Duration.ofDays(1)).toEpochMilli() / 1000)  - secs2015));
        return Stream.of(
            Arguments.of("vpro", Deletes.EXCLUDE, JAN2017),
            Arguments.of("vpro", Deletes.EXCLUDE, RANDOM),
            Arguments.of("vpro", Deletes.ID_ONLY, JAN2017),
            Arguments.of("vpro", Deletes.ID_ONLY, RANDOM),
            Arguments.of(null, Deletes.EXCLUDE, JAN2017),
            Arguments.of(null, Deletes.EXCLUDE, RANDOM),
            Arguments.of(null, Deletes.ID_ONLY, JAN2017),
            Arguments.of(null, Deletes.ID_ONLY, RANDOM)
        );
    }

    /**
     * This tries to take 100 changes from changes feed, starting from some point in time.
     * It does that in two ways:
     *
     * - 100 subsequent calls with max=1
     * - 1 call all with max=100
     *
     * The results should be exactly the same.
     *
     * Fail at os can be reproduced with : https://rs-test-os.poms.omroep.nl/v1/api/media/changes/?profile=vpro&publishedSince=1594211389690&order=asc&max=2&checkProfile=false&deletes=EXCLUDE
     */
    @ParameterizedTest
    @MethodSource("changesFeedParameters")
    public void testChangesCheckSkipDeletesMaxOne(String profile, Deletes deletes, Instant startDate) throws Exception {
        assumeTrue(apiVersionNumber.isNotBefore(Version.of(5, 4)));
        final AtomicInteger i = new AtomicInteger();
        final int toFind = 100;
        int duplicateDates = 0;
        Instant start = startDate;
        Instant prev = start;
        String prevMid = null;
        String mid = null;
        List<MediaChange> foundWithMaxOne = new ArrayList<>();
        while (i.getAndIncrement() < toFind) {
            Response response = mediaUtil.getClients().getMediaServiceNoTimeout()
                .changes(profile, null,null, sinceString(start, mid), null, 1, false, deletes, null);

            InputStream inputStream = response.readEntity(InputStream.class);
            if (response.getStatus() != 200) {
                IOUtils.copy(inputStream, LoggerOutputStream.error(Log4j2SimpleLogger.of(log)));
                throw new RuntimeException(response.readEntity(String.class));
            }



            try (JsonArrayIterator<MediaChange> changes = new JsonArrayIterator<>(inputStream, MediaChange.class, () -> closeQuietly(inputStream))) {
                MediaChange change = changes.next();
                start = change.getPublishDate();
                //noinspection deprecation
                assertThat(change.getSequence()).isNull();
                if (deletes == Deletes.EXCLUDE) {
                    assertThat(change.isDeleted()).isFalse();
                }
                if (change.isDeleted()) {
                    assertThat(change.getMedia()).isNull();
                }

                if (change.getPublishDate().equals(prev)) {
                    log.info("Found a multiple date {}", prev);
                    duplicateDates++;
                }
                assertThat(change.getPublishDate()).isAfterOrEqualTo(prev);
                //noinspection deprecation
                assertThat(change.getRevision() == null || change.getRevision() > 0).isTrue();
                if (change.isTail()) {
                    log.info("Found tail: {}", change);
                } else {
                    assertThat(change.getMid()).withFailMessage(change.getMid() + " should be different from " + mid).isNotEqualTo(mid);
                    foundWithMaxOne.add(change);
                }
                prev = change.getPublishDate();
                mid = change.getMid();
                log.info("{}", change);
            }
        }
        List<MediaChange> foundWithMax100 = new ArrayList<>();
        try (CloseableIterator<MediaChange> changes = mediaUtil.changes(profile, false, startDate, null,  Order.ASC, toFind, deletes)) {
            while (changes.hasNext()) {
                MediaChange change = changes.next();
                if (! change.isTail()) {
                    foundWithMax100.add(change);
                }
            }
        }

        assumeThat(foundWithMaxOne).isNotEmpty();
        assertThat(foundWithMaxOne).containsExactlyElementsOf(foundWithMax100);
        log.info("Found duplicate dates: {}", duplicateDates);
    }


    @Test
    public void NPA_453() throws IOException {
        //https://rs.poms.omroep.nl/v1/api/media/changes?profile=bnnvara&publishedSince=2015-03-22T03%3A43%3A05Z%2CRBX_EO_667486&order=asc&max=100&checkProfile=true&deletes=INCLUDE
        Instant start = Instant.parse("2015-03-22T03:43:05Z");
        InputStream inputStream = MediaRestClientUtils.toInputStream(mediaUtil.getClients().getMediaServiceNoTimeout()
            .changes("bnnvara", null,null, sinceString(start, "RBX_EO_667486"), "asc", 100, true, Deletes.INCLUDE, Tail.IF_EMPTY));

        try (JsonArrayIterator<MediaChange> changes = new JsonArrayIterator<>(inputStream,
            MediaChange.class, () -> IOUtils.closeQuietly(inputStream))) {
            MediaChange change = changes.next();
            log.info("{}", change);
        }



    }

    @SuppressWarnings("deprecation")
    protected void testChanges(String profile, Instant from, Integer max) throws Exception {
        Instant start = from.truncatedTo(ChronoUnit.SECONDS);
        final AtomicInteger i = new AtomicInteger();
        Instant prev = from;
        try(CloseableIterator<MediaChange> changes = mediaUtil.changes(profile, false,  from, null, Order.ASC, max, Deletes.ID_ONLY)) {
            while (changes.hasNext()) {
                MediaChange change = changes.next();
                if (!change.isTail()) {
                    log.debug("{} {}", change.getPublishDate(), change.getMid());
                    assertThat(change.getSequence()).isNull();
                    assertThat(change.getPublishDate()).withFailMessage("%s has no publish date", change).isNotNull();
                    assertThat(change.getPublishDate()).isAfterOrEqualTo(prev);
                    assertThat(change.getRevision() == null || change.getRevision() > 0).isTrue();
                    prev = change.getPublishDate();

                    if (i.incrementAndGet() % 1000 == 0) {
                        log.info("{}: {}", i.get(), change);
                    }
                }
            }
        }
        assertThat(prev.isBefore(start.minus(Duration.ofSeconds(9))));
        if (max != null) {
            assertThat(i.get()).isLessThanOrEqualTo(max);
        }
    }



    @Test
    public void testIterate() throws Exception {
        testIterate("vpro", CHANGES_MAX);

    }


    protected void testIterate(String profile, Integer max) throws Exception {
        Profile profileObject = mediaUtil.getClients().getProfileService().load(profile, null);
        try(CountedIterator<MediaObject> iterator = MediaRestClientUtils.iterate(() -> mediaUtil.getClients().getMediaServiceNoTimeout().iterate(null, profile, null, 0L, max), true, "test")) {
            int i = 0;
            while (iterator.hasNext()) {
                MediaObject mediaObject = iterator.next();
                log.info("{}: {}", ++i, mediaObject);
                assertThat(profileObject.getMediaProfile().test(mediaObject)).isTrue();
            }
            assertThat(i).isEqualTo(max);
        }
    }

    // COUCHDB only triggered if setting mediaService.changesRepository=COUCHDB on server!
    @SuppressWarnings("deprecation")
    void testChangesWithOld(String profile, Integer max) throws IOException {
        final AtomicInteger i = new AtomicInteger();
        long startSequence = couchdbSince;
        Instant prev = null;
        try (JsonArrayIterator<MediaChange> changes = mediaUtil.changes(profile, startSequence, Order.ASC, max)) {
            while (changes.hasNext()) {
                MediaChange change = changes.next();
                if (!change.isTail()) {
                    i.incrementAndGet();
                    if (i.get() >= 100) {
                        break;
                    }
                    if (apiVersionNumber.isBefore(5, 3)) {
                        assertThat(change.getSequence()).isNotNull();
                    }
                    assertThat(change.getRevision() == null || change.getRevision() > 0).isTrue();
                    if (! change.isDeleted()) {
                        if (change.getPublishDate() == null) {
                            log.warn("Publish date of {} is null", change);
                        }
                        //assertThat(change.getPublishDate()).isNotNull();
                    }
                    if (prev != null) {
                        if (change.getPublishDate() != null) { // couchdb?
                            assertThat(change.getPublishDate())
                                .isAfterOrEqualTo(prev.minus(1, ChronoUnit.MINUTES)
                                    .truncatedTo(ChronoUnit.MINUTES));
                        }
                    }
                    if (change.getPublishDate() != null) {
                        prev = change.getPublishDate();
                    }
                    log.info("{}", change);

                }
            }
        }
        assertThat(i.intValue()).isEqualTo(100);

    }

}
