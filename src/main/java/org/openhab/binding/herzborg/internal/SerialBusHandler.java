package org.openhab.binding.herzborg.internal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.TooManyListenersException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.io.transport.serial.PortInUseException;
import org.eclipse.smarthome.io.transport.serial.SerialPort;
import org.eclipse.smarthome.io.transport.serial.SerialPortEvent;
import org.eclipse.smarthome.io.transport.serial.SerialPortEventListener;
import org.eclipse.smarthome.io.transport.serial.SerialPortIdentifier;
import org.eclipse.smarthome.io.transport.serial.SerialPortManager;
import org.eclipse.smarthome.io.transport.serial.UnsupportedCommOperationException;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.Function;
import org.openhab.binding.herzborg.internal.dto.HerzborgProtocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NonNullByDefault
public class SerialBusHandler extends BaseBridgeHandler implements SerialPortEventListener {
    private final Logger logger = LoggerFactory.getLogger(SerialBusHandler.class);
    private SerialPortManager serialPortManager;
    private SerialBusConfiguration config = new SerialBusConfiguration();
    private @Nullable InputStream dataIn;
    private @Nullable OutputStream dataOut;
    private @Nullable SerialPort serialPort;

    public SerialBusHandler(Bridge bridge, SerialPortManager portManager) {
        super(bridge);
        serialPortManager = portManager;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // TODO Auto-generated method stub

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
        SerialPort port = serialPort;

        if (port == null) {
            return; // Nothing to do in this case
        }

        port.removeEventListener();
        safeClose(dataOut);
        safeClose(dataIn);
        port.close();

        dataOut = null;
        dataIn = null;
        serialPort = null;
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        try {
            logger.debug("RXTX library CPU load workaround, sleep forever");
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
        }
    }

    public synchronized @Nullable Packet doPacket(Packet pkt) throws IOException {
        OutputStream dataOut = this.dataOut;
        InputStream dataIn = this.dataIn;

        if (dataOut == null || dataIn == null) {
            return null;
        }

        int read_length = Packet.MIN_LENGTH;

        switch (pkt.getFunction()) {
            case Function.READ:
                // The reply will include data itself
                read_length += pkt.getDataLength();
                break;
            case Function.WRITE:
                // The reply is number of bytes written
                read_length += 1;
                break;
            case Function.CONTROL:
                // The whole packet will be echoed back
                read_length = pkt.getBuffer().length;
                break;
            default:
                // We must not have anything else here
                throw new IllegalStateException("Unknown function code");
        }

        dataOut.write(pkt.getBuffer());

        int read_offset = 0;
        byte[] in_buffer = new byte[read_length];

        while (read_length > 0) {
            int n = dataIn.read(in_buffer, read_offset, read_length);

            if (n < 0) {
                throw new IOException("EOF from serial port");
            } else if (n == 0) {
                throw new IOException("Serial read timeout");
            }

            read_offset += n;
            read_length -= n;
        }

        return new Packet(in_buffer);
    }

    public void Flush() throws IOException {
        InputStream dataIn = this.dataIn;

        if (dataIn != null) {
            // Unfortunately Java streams can't be flushed. Just read and drop all the characters
            while (dataIn.available() > 0) {
                dataIn.read();
            }
        }
    }
}
