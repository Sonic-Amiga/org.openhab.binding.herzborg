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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.TooManyListenersException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.io.transport.serial.PortInUseException;
import org.eclipse.smarthome.io.transport.serial.SerialPort;
import org.eclipse.smarthome.io.transport.serial.SerialPortEvent;
import org.eclipse.smarthome.io.transport.serial.SerialPortEventListener;
import org.eclipse.smarthome.io.transport.serial.SerialPortIdentifier;
import org.eclipse.smarthome.io.transport.serial.SerialPortManager;
import org.eclipse.smarthome.io.transport.serial.UnsupportedCommOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SerialBusHandler} implements specific handling for Herzborg serial bus,
 * connected directly via a serial port.
 *
 * @author Pavel Fedin - Initial contribution
 */
@NonNullByDefault
public class SerialBusHandler extends BusHandler implements SerialPortEventListener {
    private final Logger logger = LoggerFactory.getLogger(SerialBusHandler.class);
    private SerialPortManager serialPortManager;
    private SerialBusConfiguration config = new SerialBusConfiguration();
    private @Nullable SerialPort serialPort;

    public SerialBusHandler(Bridge bridge, SerialPortManager portManager) {
        super(bridge);
        serialPortManager = portManager;
    }

    @Override
    public void initialize() {
        config = getConfigAs(SerialBusConfiguration.class);

        SerialPortIdentifier portIdentifier = serialPortManager.getIdentifier(config.port);
        if (portIdentifier == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No such port: " + config.port);
            return;
        }

        SerialPort commPort;
        try {
            commPort = portIdentifier.open(this.getClass().getName(), 2000);
        } catch (PortInUseException e1) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Port " + config.port + " is in use");
            return;
        }

        try {
            // Herzborg serial bus operates with fixed parameters
            commPort.setSerialPortParams(9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
            commPort.enableReceiveThreshold(8);
            commPort.enableReceiveTimeout(1000);
            commPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
        } catch (UnsupportedCommOperationException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid port configuration");
            return;
        }

        InputStream dataIn = null;
        OutputStream dataOut = null;
        String error = null;

        try {
            dataIn = commPort.getInputStream();
            dataOut = commPort.getOutputStream();

            if (dataIn == null) {
                error = "No input stream available on the serial port";
            } else if (dataOut == null) {
                error = "No output stream available on the serial port";
            } else {
                dataOut.flush();
                if (dataIn.markSupported()) {
                    dataIn.reset();
                }

                // RXTX serial port library causes high CPU load
                // Start event listener, which will just sleep and slow down event
                // loop
                // This is copied from SonyProjector binding
                commPort.addEventListener(this);
                commPort.notifyOnDataAvailable(true);
            }
        } catch (IOException | TooManyListenersException e) {
            error = e.getMessage();
        }

        if (error != null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR, error);
            return;
        }

        this.serialPort = commPort;
        this.dataIn = dataIn;
        this.dataOut = dataOut;

        logger.trace("Successfully initialized");

        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void dispose() {
        SerialPort port = serialPort;

        if (port == null) {
            return; // Nothing to do in this case
        }

        port.removeEventListener();
        super.dispose();
        port.close();
        serialPort = null;
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        // Nothing to do here
    }
}
