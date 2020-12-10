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

import static org.openhab.binding.herzborg.internal.HerzborgBindingConstants.*;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.StringType;
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
        String ch = channelUID.getId();
        Packet pkt = null;

        switch (ch) {
            case CHANNEL_POSITION:
                if (command instanceof UpDownType) {
                    pkt = buildPacket(Function.CONTROL,
                            (command == UpDownType.UP) ? ControlAddress.OPEN : ControlAddress.CLOSE);
                } else if (command instanceof StopMoveType) {
                    pkt = buildPacket(Function.CONTROL, ControlAddress.STOP);
                } else if (command instanceof DecimalType) {
                    pkt = buildPacket(Function.CONTROL, ControlAddress.PERCENT, ((DecimalType) command).byteValue());
                }
                break;
            case CHANNEL_REVERSE:
                if (command instanceof OnOffType) {
                    pkt = buildPacket(Function.WRITE, DataAddress.DEFAULT_DIR, command.equals(OnOffType.ON) ? 1 : 0);
                }
                break;
            case CHANNEL_HAND_START:
                if (command instanceof OnOffType) {
                    pkt = buildPacket(Function.WRITE, DataAddress.HAND_START, command.equals(OnOffType.ON) ? 0 : 1);
                }
                break;
            case CHANNEL_EXT_SWITCH:
                if (command instanceof StringType) {
                    pkt = buildPacket(Function.WRITE, DataAddress.EXT_SWITCH, Byte.valueOf(command.toString()));
                }
                break;
            case CHANNEL_HV_SWITCH:
                if (command instanceof StringType) {
                    pkt = buildPacket(Function.WRITE, DataAddress.EXT_HV_SWITCH, Byte.valueOf(command.toString()));
                }
                break;
        }

        if (pkt != null) {
            final Packet p = pkt;
            scheduler.schedule(() -> {
                Packet reply = doPacket(p);

                if (reply != null) {
                    logger.trace("Function {} addr {} reply {}", p.getFunction(), p.getDataAddress(),
                            DatatypeConverter.printHexBinary(reply.getBuffer()));
                }
            }, 0, TimeUnit.MILLISECONDS);
        }
    }

    private Packet buildPacket(byte function, byte data_addr) {
        return new Packet((short) config.address, function, data_addr);
    }

    private Packet buildPacket(byte function, byte data_addr, byte value) {
        return new Packet((short) config.address, function, data_addr, value);
    }

    private Packet buildPacket(byte function, byte data_addr, int value) {
        return buildPacket(function, data_addr, (byte) value);
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
        Packet reply = doPacket(buildPacket(Function.READ, DataAddress.POSITION, 4));

        if (reply != null) {
            byte position = reply.getData(0);
            byte reverse = reply.getData(1);
            byte hand_start = reply.getData(2);
            byte mode = reply.getData(3);

            if (position > 100 || position < 0) {
                // If calibration has been lost, position is reported as -1.
                // Unfortinately DecimalType seems not to allow NaN, so we have to
                // fall back to some valid value, we choose 0
                position = 0;
            }

            updateState(CHANNEL_POSITION, new PercentType(position));
            updateState(CHANNEL_REVERSE, reverse != 0 ? OnOffType.ON : OnOffType.OFF);
            updateState(CHANNEL_HAND_START, hand_start == 0 ? OnOffType.ON : OnOffType.OFF);
            updateState(CHANNEL_MODE, new StringType(String.valueOf(mode)));
        }

        Packet ext_reply = doPacket(buildPacket(Function.READ, DataAddress.EXT_SWITCH, 2));

        if (ext_reply != null) {
            byte ext_switch = ext_reply.getData(0);
            byte hv_switch = ext_reply.getData(1);

            updateState(CHANNEL_EXT_SWITCH, new StringType(String.valueOf(ext_switch)));
            updateState(CHANNEL_HV_SWITCH, new StringType(String.valueOf(hv_switch)));
        }
    }
}
