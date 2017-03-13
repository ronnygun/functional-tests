package nl.vpro.poms.integration;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import nl.vpro.domain.media.Location;
import nl.vpro.domain.media.MediaTestDataBuilder;
import nl.vpro.domain.media.Program;
import nl.vpro.domain.media.Segment;
import nl.vpro.domain.media.support.Image;
import nl.vpro.domain.media.update.GroupUpdate;
import nl.vpro.domain.media.update.ProgramUpdate;
import nl.vpro.poms.AbstractApiMediaBackendTest;

import static nl.vpro.poms.Utils.waitUntil;
import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.Assume.assumeNotNull;

/**
 * Create and change items, and check them in frontend api
 * @author Michiel Meeuwissen
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Slf4j
public class MediaITest extends AbstractApiMediaBackendTest {

    static String groupMid;
    static String clipMid;
    static String clipTitle;
    static String clipDescription;

    @Test
    public void test001CreateMedia() throws Exception {
        clipTitle = title;
        Image expiredImage = createImage();
        expiredImage.setTitle("OFFLINE" + title);
        expiredImage.setPublishStopInstant(Instant.now().minus(Duration.ofMinutes(1)));

        Image publishedImage = createImage();
        publishedImage.setTitle("PUBLISHED" + title);
        publishedImage.setPublishStopInstant(Instant.now().plus(Duration.ofMinutes(10)));

        Segment expiredSegment= createSegment();
        expiredSegment.setMainTitle("OFFLINE" + title);
        expiredSegment.setPublishStopInstant(Instant.now().minus(Duration.ofMinutes(1)));

        Segment publishedSegment = createSegment();
        publishedSegment.setMainTitle("PUBLISHED" + title);
        publishedSegment.setPublishStopInstant(Instant.now().plus(Duration.ofMinutes(10)));

        Location expiredLocation = createLocation(1);
        expiredLocation.setPublishStopInstant(Instant.now().minus(Duration.ofMinutes(1)));

        Location publishedLocation = createLocation(2);
        publishedLocation.setPublishStopInstant(Instant.now().plus(Duration.ofMinutes(10)));

        clipMid = backend.set(
            ProgramUpdate
                .create(
                    MediaTestDataBuilder
                        .clip()
                        .constrainedNew()
                        .clearBroadcasters()
                        .broadcasters("VPRO")
                        .mainTitle(clipTitle)
                        .withAgeRating()
                        .images(
                            expiredImage,
                            publishedImage
                        )
                        .segments(
                            expiredSegment,
                            publishedSegment
                        )
                        .locations(
                            expiredLocation,
                            publishedLocation
                        )
                )
        );
        log.info("Created clip {} {}", clipMid, clipTitle);
        groupMid = backend.set(
            GroupUpdate.create(
                MediaTestDataBuilder
                    .playlist()
                    .constrainedNew()
                    .mainTitle(title)
                    .clearBroadcasters()
                    .withAgeRating()
                    .broadcasters("VPRO")
            ));
        String offlineGroup = backend.set(
            GroupUpdate.create(
                MediaTestDataBuilder
                    .playlist()
                    .constrainedNew()
                    .mainTitle(title + " offline")
                    .publishStop(Instant.now().minus(Duration.ofMinutes(5)))
                    .clearBroadcasters()
                    .withAgeRating()
                    .broadcasters("VPRO")
            ));
        waitUntil(Duration.ofMinutes(2),
            clipMid + " and " + groupMid + " available",
            () -> backend.getFull(clipMid) != null && backend.getFull(groupMid) != null
        );
        log.info("Created groups {}, {}", groupMid, offlineGroup);
        backend.createMember(offlineGroup, clipMid, 1);
        backend.createMember(groupMid, clipMid, 2);
    }

    @Test
    public void test002CheckFrontendApi() throws Exception {
        assumeNotNull(clipMid);
        Program clip = waitUntil(Duration.ofMinutes(10),
            clipMid + " is a memberof " + groupMid,
            () -> mediaUtil.findByMid(clipMid),
            (c) -> !c.getMemberOf().isEmpty());
        assertThat(clip).isNotNull();
        assertThat(clip.getMainTitle()).isEqualTo(clipTitle);
        assertThat(clip.getMemberOf().first().getMediaRef()).isEqualTo(groupMid);
        assertThat(clip.getMemberOf().first().getNumber()).isEqualTo(2);
        assertThat(clip.getMemberOf()).hasSize(1);
        assertThat(clip.getImages()).hasSize(1);
        assertThat(clip.getSegments()).hasSize(1);
        assertThat(clip.getLocations()).hasSize(1);
    }

    @Test
    public void test003UpdateTitle() {
        assumeNotNull(clipMid);
        ProgramUpdate mediaUpdate = backend.get(clipMid);
        clipTitle = title;
        mediaUpdate.setMainTitle(clipTitle);
        backend.set(mediaUpdate);
    }

    @Test
    public void test004CheckFrontendApi() throws Exception {
        assumeNotNull(clipMid);
        Program clip = waitUntil(Duration.ofMinutes(10),
            clipMid + " has title " + clipTitle,
            () -> mediaUtil.findByMid(clipMid),
            (c) -> c.getMainTitle().equals(clipTitle));
        assertThat(clip).isNotNull();
        assertThat(clip.getMainTitle()).isEqualTo(clipTitle);
    }


    @Test
    public void test005UpdateDescription() {
        assumeNotNull(clipMid);
        ProgramUpdate mediaUpdate = backend.get(clipMid);
        clipDescription = title;
        mediaUpdate.setMainDescription(clipDescription);
        backend.set(mediaUpdate);
    }

    @Test
    public void test006CheckFrontendApi() throws Exception {
        assumeNotNull(clipMid);
        Program clip = waitUntil(Duration.ofMinutes(10),
            clipMid + " has description " + clipDescription,
            () -> mediaUtil.findByMid(clipMid),
            (c) -> c.getMainDescription().equals(clipDescription));
        assertThat(clip).isNotNull();
        assertThat(clip.getMainDescription()).isEqualTo(clipDescription);
        assertThat(clip.getMainTitle()).isEqualTo(clipTitle);
    }


    @Test
    public void test007WaitForImageRevocation() throws Exception {
        assumeNotNull(clipMid);
        Program clip = waitUntil(Duration.ofMinutes(20),
            clipMid + " has no images any more",
            () -> mediaUtil.findByMid(clipMid),
            (c) -> c.getImages().isEmpty());
        assertThat(clip).isNotNull();
        assertThat(clip.getImages()).isEmpty();
    }

    @Test
    public void test007WaitForSegmentRevocation() throws Exception {
        assumeNotNull(clipMid);
        Program clip = waitUntil(Duration.ofMinutes(20),
            clipMid + " has no segments any more",
            () -> mediaUtil.findByMid(clipMid),
            (c) -> c.getSegments().isEmpty());
        assertThat(clip).isNotNull();
        assertThat(clip.getSegments()).isEmpty();
    }

    @Test
    public void test008WaitForLocationsRevocation() throws Exception {
        assumeNotNull(clipMid);
        Program clip = waitUntil(Duration.ofMinutes(20),
            clipMid + " has no locations any more",
            () -> mediaUtil.findByMid(clipMid),
            (c) -> c.getLocations().isEmpty());
        assertThat(clip).isNotNull();
        assertThat(clip.getLocations()).isEmpty();
    }

    @Test
    public void test100Delete() throws Exception {
        assumeNotNull(clipMid);
        backend.delete(clipMid);
    }

    @Test
    public void test101CheckFrontendApi() throws Exception {
        assumeNotNull(clipMid);
        assertThat(waitUntil(Duration.ofMinutes(10),
            clipMid + " disappeared",
            () -> mediaUtil.findByMid(clipMid) == null
        )).isTrue();

    }
}
