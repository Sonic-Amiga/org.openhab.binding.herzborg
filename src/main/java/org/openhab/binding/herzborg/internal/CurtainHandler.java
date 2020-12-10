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

import static org.openhab.binding.herzborg.internal.HerzborgBindingConstants.CHANNEL_POSITION;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.BridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.ControlAddress;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.DataAddress;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.Function;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CurtainHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Pavel Fedin - Initial contribution
 */
@NonNullByDefault
public class CurtainHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(CurtainHandler.class);

    private CurtainConfiguration config = new CurtainConfiguration();
    private @Nullable ScheduledFuture<?> pollFuture;
    private @Nullable SerialBusHandler bus;

    public CurtainHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (CHANNEL_POSITION.equals(channelUID.getId())) {
            Packet pkt = null;

            if (command instanceof UpDownType) {
                pkt = buildPacket(Function.CONTROL,
                        (command == UpDownType.UP) ? ControlAddress.OPEN : ControlAddress.CLOSE);
            } else if (command instanceof StopMoveType) {
                pkt = buildPacket(Function.CONTROL, ControlAddress.STOP);
            } else if (command instanceof DecimalType) {
                pkt = buildPacket(Function.CONTROL, ControlAddress.PERCENT, ((DecimalType) command).byteValue());
            }

            if (pkt != null) {
                final Packet p = pkt;
                scheduler.schedule(() -> {
                    doPacket(p);
                }, 0, TimeUnit.MILLISECONDS);
            }
        }
    }

    private Packet buildPacket(byte function, byte data_addr) {
        return new Packet((short) config.address, function, data_addr);
    }

    private Packet buildPacket(byte function, byte data_addr, byte value) {
        return new Packet((short) config.address, function, data_addr, value);
    }

    @Override
    public void initialize() {
        Bridge bridge = getBridge();

        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR, "Bridge not present");
            return;
        }

        BridgeHandler handler = bridge.getHandler();

        if (handler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR, "Bridge has no handler");
            return;
        }

        bus = (SerialBusHandler) handler;
        config = getConfigAs(CurtainConfiguration.class);

        updateStatus(ThingStatus.UNKNOWN);
        logger.trace("Successfully initialized, starting poll");
        pollFuture = scheduler.scheduleWithFixedDelay(this::poll, 1, config.poll_interval, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        stopPoll();
    }

    private void stopPoll() {
        if (pollFuture != null) {
            pollFuture.cancel(true);
            pollFuture = null;
        }
    }

    private @Nullable synchronized Packet doPacket(Packet pkt) {
        SerialBusHandler bus = this.bus;

        if (bus == null) {
            // This is an impossible situation but Eclipse forces us to handle it
            logger.warn("No Bridge sending commands");
            return null;
        }

        try {
            Packet reply = bus.doPacket(pkt);

            if (reply == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                return null;
            }

            if (reply.isValid()) {
                updateStatus(ThingStatus.ONLINE);
                return reply;
            } else {
                logger.warn("Invalid reply received: {}", DatatypeConverter.printHexBinary(reply.getBuffer()));
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid response received");
                bus.Flush();
            }

        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }

        return null;
    }

    private void poll() {
        Packet pkt = new Packet((short) config.address, Function.READ, DataAddress.POSITION, (byte) 1);
        Packet reply = doPacket(pkt);

        if (reply != null) {
            updateState(CHANNEL_POSITION, new PercentType(reply.getData()));
        }
    }
}
