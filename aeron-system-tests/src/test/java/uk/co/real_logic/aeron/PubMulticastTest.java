/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron;

import org.junit.*;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor;
import uk.co.real_logic.aeron.common.event.EventLogger;
import uk.co.real_logic.aeron.common.protocol.*;
import uk.co.real_logic.aeron.driver.MediaDriver;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static uk.co.real_logic.aeron.common.CommonContext.DIRS_DELETE_ON_EXIT_PROP_NAME;

/**
 * Test that has a publisher and single media driver for multicast cases. Uses socket as receiver/consumer endpoint.
 */
public class PubMulticastTest
{
    public static final EventLogger LOGGER = new EventLogger(PubMulticastTest.class);

    private static final String DATA_ADDRESS = "224.20.30.39";
    private static final String CONTROL_ADDRESS = "224.20.30.40";
    private static final int DST_PORT = 54321;
    private static final String DESTINATION = "udp://localhost@" + DATA_ADDRESS + ":" + DST_PORT;
    private static final long CHANNEL_ID = 1L;
    private static final long SESSION_ID = 2L;
    private static final byte[] PAYLOAD = "Payload goes here!".getBytes();

    private final AtomicBuffer payload = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));

    private final InetSocketAddress controlAddress = new InetSocketAddress(CONTROL_ADDRESS, DST_PORT);

    private Aeron producingClient;
    private MediaDriver driver;
    private Publication publication;

    private DatagramChannel receiverChannel;

    private final DataHeaderFlyweight dataHeader = new DataHeaderFlyweight();
    private final StatusMessageFlyweight statusMessage = new StatusMessageFlyweight();
    private final NakFlyweight nakHeader = new NakFlyweight();

    private ExecutorService executorService;

    @Before
    public void setupClientAndMediaDriver() throws Exception
    {
        System.setProperty(DIRS_DELETE_ON_EXIT_PROP_NAME, "true");

        final NetworkInterface ifc = NetworkInterface.getByInetAddress(InetAddress.getByName("localhost"));
        receiverChannel = DatagramChannel.open();
        receiverChannel.configureBlocking(false);
        receiverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        receiverChannel.bind(new InetSocketAddress(DST_PORT));
        receiverChannel.join(InetAddress.getByName(DATA_ADDRESS), ifc);
        receiverChannel.setOption(StandardSocketOptions.IP_MULTICAST_IF, ifc);

        final MediaDriver.DriverContext ctx = new MediaDriver.DriverContext();

        ctx.warnIfDirectoriesExist(false);

        driver = new MediaDriver(ctx);

        producingClient = Aeron.newSingleMediaDriver(newAeronContext());

        payload.putBytes(0, PAYLOAD);

        executorService = Executors.newSingleThreadExecutor();

        driver.invokeEmbedded();
        producingClient.invoke(executorService);

        publication = producingClient.addPublication(DESTINATION, CHANNEL_ID, SESSION_ID);
    }

    private Aeron.ClientContext newAeronContext()
    {
        Aeron.ClientContext ctx = new Aeron.ClientContext();

        return ctx;
    }

    @After
    public void closeEverything() throws Exception
    {
        publication.release();

        producingClient.shutdown();
        driver.shutdown();

        receiverChannel.close();
        producingClient.close();
        driver.close();
        executorService.shutdown();
    }

    @Test(timeout = 1000)
    public void shouldSendCorrectlyFormedSingleDataFrames() throws Exception
    {
        LOGGER.logInvocation();

        // let buffers get connected

        // this will not be sent yet
        while (!publication.offer(payload, 0, PAYLOAD.length))
        {
            Thread.yield();
        }

        final AtomicLong termId = new AtomicLong();
        final AtomicLong rcvedZeroLengthData = new AtomicLong(0);

        // should only see 0 length data until SM is sent
        DatagramTestHelper.receiveUntil(receiverChannel,
            (buffer) ->
            {
                dataHeader.wrap(buffer, 0);
                if (dataHeader.headerType() == HeaderFlyweight.HDR_TYPE_DATA)
                {
                    assertThat(dataHeader.channelId(), is(CHANNEL_ID));
                    assertThat(dataHeader.sessionId(), is(SESSION_ID));
                    termId.set(dataHeader.termId());

                    assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH));
                    assertThat(buffer.position(), is(DataHeaderFlyweight.HEADER_LENGTH));
                    rcvedZeroLengthData.incrementAndGet();
                    return true;
                }

                return false;
            });

        assertThat(rcvedZeroLengthData.get(), greaterThanOrEqualTo(1L));

        // send SM
        sendSM(termId.get());

        final AtomicLong rcvedDataFrames = new AtomicLong(0);

        // assert the received Data Frames are correctly formed. Either SM or real data
        DatagramTestHelper.receiveUntil(receiverChannel,
            (buffer) ->
            {
                dataHeader.wrap(buffer, 0);
                assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
                assertThat(dataHeader.channelId(), is(CHANNEL_ID));
                assertThat(dataHeader.sessionId(), is(SESSION_ID));
                assertThat(dataHeader.termId(), is(termId.get()));

                if (dataHeader.frameLength() > DataHeaderFlyweight.HEADER_LENGTH)
                {
                    assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
//                    assertThat(buffer.position(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length);
                    rcvedDataFrames.incrementAndGet();
                    return true;
                }

                return false;
            });

        assertThat(rcvedDataFrames.get(), is(1L));
    }

    @Test(timeout = 100000)
    public void shouldHandleNak() throws Exception
    {
        LOGGER.logInvocation();

        // let buffers get connected

        // this will not be sent yet
        while (!publication.offer(payload, 0, PAYLOAD.length))
        {
            Thread.yield();
        }

        final AtomicLong termId = new AtomicLong();
        final AtomicLong rcvedZeroLengthData = new AtomicLong();
        final AtomicLong rcvedDataFrames = new AtomicLong();

        // should only see 0 length data until SM is sent
        DatagramTestHelper.receiveUntil(receiverChannel,
            (buffer) ->
            {
                dataHeader.wrap(buffer, 0);
                if (dataHeader.headerType() == HeaderFlyweight.HDR_TYPE_DATA &&
                    dataHeader.frameLength() == DataHeaderFlyweight.HEADER_LENGTH)
                {
                    termId.set(dataHeader.termId());
                    rcvedZeroLengthData.incrementAndGet();
                    return true;
                }

                return false;
            });

        assertThat(rcvedZeroLengthData.get(), greaterThanOrEqualTo(1L));

        // send SM
        sendSM(termId.get());

        // assert the received Data Frames are correct
        DatagramTestHelper.receiveUntil(receiverChannel,
            (buffer) ->
            {
                dataHeader.wrap(buffer, 0);
                assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));

                if (dataHeader.frameLength() > DataHeaderFlyweight.HEADER_LENGTH)
                {
                    rcvedDataFrames.incrementAndGet();
                    return true;
                }

                return false;
            });

        assertThat(rcvedDataFrames.get(), greaterThanOrEqualTo(1L));

        // send NAK
        sendNak(termId.get(), 0, FrameDescriptor.FRAME_ALIGNMENT);

        // assert the received Data Frames are correct
        DatagramTestHelper.receiveUntil(receiverChannel,
            (buffer) ->
            {
                dataHeader.wrap(buffer, 0);
                assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
                assertThat(dataHeader.channelId(), is(CHANNEL_ID));
                assertThat(dataHeader.sessionId(), is(SESSION_ID));
                assertThat(dataHeader.termId(), is(termId.get()));

                if (dataHeader.frameLength() > DataHeaderFlyweight.HEADER_LENGTH)
                {
                    assertThat(dataHeader.frameLength(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
//                    assertThat(rcvBuffer.position(), is(DataHeaderFlyweight.HEADER_LENGTH + PAYLOAD.length));
                    rcvedDataFrames.incrementAndGet();
                    return true;
                }

                return false;
            });

        assertThat(rcvedDataFrames.get(), greaterThanOrEqualTo(2L));
    }

    @Test(timeout = 1000)
    @Ignore("isn't finished yet - send enough data to rollover a buffer")
    public void messagesShouldContinueAfterBufferRollover()
    {
        LOGGER.logInvocation();
    }

    private void sendSM(final long termId) throws Exception
    {
        final ByteBuffer smBuffer = ByteBuffer.allocate(StatusMessageFlyweight.HEADER_LENGTH);
        statusMessage.wrap(new AtomicBuffer(smBuffer), 0);

        statusMessage.receiverWindow(1000)
                .highestContiguousTermOffset(0)
                .termId(termId)
                .channelId(CHANNEL_ID)
                .sessionId(SESSION_ID)
                .frameLength(StatusMessageFlyweight.HEADER_LENGTH)
                .headerType(HeaderFlyweight.HDR_TYPE_SM)
                .flags((short) 0)
                .version(HeaderFlyweight.CURRENT_VERSION);

        smBuffer.position(0);
        smBuffer.limit(StatusMessageFlyweight.HEADER_LENGTH);
        final int bytesSent = receiverChannel.send(smBuffer, controlAddress);

        assertThat(bytesSent, is(StatusMessageFlyweight.HEADER_LENGTH));
    }

    private void sendNak(final long termId, final long termOffset, final long length) throws Exception
    {
        final ByteBuffer nakBuffer = ByteBuffer.allocate(NakFlyweight.HEADER_LENGTH);
        nakHeader.wrap(new AtomicBuffer(nakBuffer), 0);

        nakHeader.length(length)
                .termOffset(termOffset)
                .termId(termId)
                .channelId(CHANNEL_ID)
                .sessionId(SESSION_ID)
                .frameLength(NakFlyweight.HEADER_LENGTH)
                .headerType(HeaderFlyweight.HDR_TYPE_NAK)
                .flags((short) 0)
                .version(HeaderFlyweight.CURRENT_VERSION);

        nakBuffer.position(0);
        nakBuffer.limit(NakFlyweight.HEADER_LENGTH);
        final int bytesSent = receiverChannel.send(nakBuffer, controlAddress);

        assertThat(bytesSent, is(NakFlyweight.HEADER_LENGTH));
    }
}
