/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.herzborg.internal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.Function;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BusHandler} is a handy base class, implementing data communication with Herzborg devices.
 *
 * @author Pavel Fedin - Initial contribution
 */
@NonNullByDefault
public abstract class BusHandler extends BaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(BusHandler.class);

    protected @Nullable InputStream dataIn;
    protected @Nullable OutputStream dataOut;

    public BusHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Nothing to do here, but we have to implement it
    }

    private void safeClose(@Nullable Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                logger.warn("Error closing I/O stream: {}", e.getMessage());
            }
        }
    }

    @Override
    public void dispose() {
        safeClose(dataOut);
        safeClose(dataIn);

        dataOut = null;
        dataIn = null;
    }

    public synchronized @Nullable Packet doPacket(Packet pkt) throws IOException {
        OutputStream dataOut = this.dataOut;
        InputStream dataIn = this.dataIn;

        if (dataOut == null || dataIn == null) {
            return null;
        }

        int readLength = Packet.MIN_LENGTH;

        switch (pkt.getFunction()) {
            case Function.READ:
                // The reply will include data itself
                readLength += pkt.getDataLength();
                break;
            case Function.WRITE:
                // The reply is number of bytes written
                readLength += 1;
                break;
            case Function.CONTROL:
                // The whole packet will be echoed back
                readLength = pkt.getBuffer().length;
                break;
            default:
                // We must not have anything else here
                throw new IllegalStateException("Unknown function code");
        }

        dataOut.write(pkt.getBuffer());

        int readOffset = 0;
        byte[] replyBuffer = new byte[readLength];

        while (readLength > 0) {
            int n = dataIn.read(replyBuffer, readOffset, readLength);

            if (n < 0) {
                throw new IOException("EOF from serial port");
            } else if (n == 0) {
                throw new IOException("Serial read timeout");
            }

            readOffset += n;
            readLength -= n;
        }

        return new Packet(replyBuffer);
    }

    public void flush() throws IOException {
        InputStream dataIn = this.dataIn;

        if (dataIn != null) {
            // Unfortunately Java streams can't be flushed. Just read and drop all the characters
            while (dataIn.available() > 0) {
                dataIn.read();
            }
        }
    }
}
