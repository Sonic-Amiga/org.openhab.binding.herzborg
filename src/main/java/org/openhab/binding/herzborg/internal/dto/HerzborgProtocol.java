package org.openhab.binding.herzborg.internal.dto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class HerzborgProtocol {

    public static class Function {
        public static final byte READ = 0x01;
        public static final byte WRITE = 0x02;
        public static final byte CONTROL = 0x03;
        public static final byte REQUEST = 0x04;
    }

    public static class ControlAddress {
        public static final byte OPEN = 0x01;
        public static final byte CLOSE = 0x02;
        public static final byte STOP = 0x03;
        public static final byte PERCENT = 0x04;
        public static final byte DELETE_LIMIT = 0x07;
        public static final byte DEFAULT = 0x08;
        public static final byte SET_CONTEXT = 0x09;
        public static final byte RUN_CONTEXT = 0x0A;
        public static final byte DEL_CONTEXT = 0x0B;
    }

    public static class DataAddress {
        public static final byte ID_L = 0x00;
        public static final byte ID_H = 0x01;
        public static final byte POSITION = 0x02;
        public static final byte DEFAULT_DIR = 0x03;
        public static final byte HAND_START = 0x04;
        public static final byte MODE = 0x05;
        public static final byte EXT_SWITCH = 0x27;
        public static final byte EXT_HV_SWITCH = 0x28;
    }

    public static class Packet {
        private static final int HEADER_LENGTH = 5;
        private static final int CRC16_LENGTH = 2;
        public static final int MIN_LENGTH = HEADER_LENGTH + CRC16_LENGTH;

        private static final byte START = 0x55;

        private ByteBuffer m_Buffer;
        private int m_DataLength;

        public Packet(byte[] data) {
            m_Buffer = ByteBuffer.wrap(data);
            m_Buffer.order(ByteOrder.LITTLE_ENDIAN);
            m_DataLength = data.length - CRC16_LENGTH;
        }

        public Packet(short device_addr, byte function, byte data_addr) {
            m_DataLength = HEADER_LENGTH;

            if (function == Function.READ) {
                m_DataLength++;
            }

            m_Buffer = ByteBuffer.allocate(m_DataLength + CRC16_LENGTH);
            m_Buffer.order(ByteOrder.LITTLE_ENDIAN);

            m_Buffer.put(START);
            m_Buffer.putShort(device_addr);
            m_Buffer.put(function);
            m_Buffer.put(data_addr);

            if (function == Function.READ) {
                m_Buffer.put((byte) 1);
            }

            m_Buffer.putShort(crc16(m_DataLength));
        }

        public byte[] getBuffer() {
            return m_Buffer.array();
        }

        public boolean isValid() {
            return m_Buffer.get(0) == START && crc16(m_DataLength) == m_Buffer.getShort(m_DataLength);
        }

        public byte getFunction() {
            return m_Buffer.get(3);
        }

        public byte getDataAddress() {
            return m_Buffer.get(4);
        }

        public byte getDataLength() {
            return m_Buffer.get(HEADER_LENGTH);
        }

        public byte getData() {
            return m_Buffer.get(HEADER_LENGTH);
        }

        // Herzborg uses modbus variant of CRC16
        // Code adapted from https://habr.com/ru/post/418209/
        private short crc16(int length) {
            int crc = 0xFFFF;
            for (int i = 0; i < length; i++) {
                crc = crc ^ Byte.toUnsignedInt(m_Buffer.get(i));
                for (int j = 0; j < 8; j++) {
                    int mask = ((crc & 0x1) != 0) ? 0xA001 : 0x0000;
                    crc = ((crc >> 1) & 0x7FFF) ^ mask;
                }
            }
            return (short) crc;
        }
    }
}
