package com.paritytrading.philadelphia;

import static com.paritytrading.philadelphia.FIX.BEGIN_STRING;
import static com.paritytrading.philadelphia.FIX.BEGIN_STRING_FIELD_CAPACITY;
import static com.paritytrading.philadelphia.FIX.BODY_LENGTH;
import static com.paritytrading.philadelphia.FIX.BODY_LENGTH_FIELD_CAPACITY;
import static com.paritytrading.philadelphia.FIX.CHECK_SUM;
import static com.paritytrading.philadelphia.FIX.CHECK_SUM_FIELD_CAPACITY;
import static com.paritytrading.philadelphia.FIXMsgTypes.Heartbeat;
import static com.paritytrading.philadelphia.FIXMsgTypes.Logon;
import static com.paritytrading.philadelphia.FIXMsgTypes.Logout;
import static com.paritytrading.philadelphia.FIXMsgTypes.Reject;
import static com.paritytrading.philadelphia.FIXMsgTypes.ResendRequest;
import static com.paritytrading.philadelphia.FIXMsgTypes.SequenceReset;
import static com.paritytrading.philadelphia.FIXMsgTypes.TestRequest;
import static com.paritytrading.philadelphia.FIXSessionRejectReasons.RequiredTagMissing;
import static com.paritytrading.philadelphia.FIXSessionRejectReasons.ValueIsIncorrect;
import static com.paritytrading.philadelphia.FIXTags.BeginSeqNo;
import static com.paritytrading.philadelphia.FIXTags.EncryptMethod;
import static com.paritytrading.philadelphia.FIXTags.EndSeqNo;
import static com.paritytrading.philadelphia.FIXTags.GapFillFlag;
import static com.paritytrading.philadelphia.FIXTags.HeartBtInt;
import static com.paritytrading.philadelphia.FIXTags.MsgSeqNum;
import static com.paritytrading.philadelphia.FIXTags.MsgType;
import static com.paritytrading.philadelphia.FIXTags.NewSeqNo;
import static com.paritytrading.philadelphia.FIXTags.PossDupFlag;
import static com.paritytrading.philadelphia.FIXTags.RefSeqNum;
import static com.paritytrading.philadelphia.FIXTags.ResetSeqNumFlag;
import static com.paritytrading.philadelphia.FIXTags.SenderCompID;
import static com.paritytrading.philadelphia.FIXTags.SendingTime;
import static com.paritytrading.philadelphia.FIXTags.SessionRejectReason;
import static com.paritytrading.philadelphia.FIXTags.TargetCompID;
import static com.paritytrading.philadelphia.FIXTags.TestReqID;
import static com.paritytrading.philadelphia.FIXTags.Text;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;

/**
 * A connection.
 */
public class FIXConnection implements Closeable {

    private final Clock clock;

    private final SocketChannel channel;

    private final FIXConfig config;

    private final FIXValue bodyLength;
    private final FIXValue checkSum;

    protected String senderCompId;
    protected String targetCompId;

    private long rxMsgSeqNum;
    private long txMsgSeqNum;

    private final ByteBuffer rxBuffer;

    private final ByteBuffer txHeaderBuffer;

    private final int bodyLengthOffset;

    private final ByteBuffer txBodyBuffer;

    private final ByteBuffer[] txBuffers;
    
    
    //added to support FIX logging
    protected FIXValue bodyLengthValue;

    boolean appendToExistingLogs = false;	
    
    public final static int FIX_BUFFER_SIZE = 1024 * 16;
    
    private byte[] outputLogBuffer = new byte[FIX_BUFFER_SIZE];
    private ByteBuffer outputLogByteBuffer = ByteBuffer.wrap(outputLogBuffer);


    /*
     * This variable is written on data reception and read on connection
     * keep-alive. These two functions can run on different threads
     * without locking.
     */
    private volatile long lastRxMillis;

    /*
     * This variable is written on data transmission and read on connection
     * keep-alive. These two functions can run on different threads but
     * require locking.
     */
    private long lastTxMillis;

    private long testRequestTxMillis;

    private final long heartbeatMillis;

    private final long testRequestMillis;

    private final FIXMessageParser parser;

    private final FIXConnectionStatusListener statusListener;

    private final FIXMessage txMessage;

    private long currentTimeMillis;

    private final MutableDateTime currentTime;

    private final StringBuilder currentTimestamp;
    
    private final FIXValue beginString;
    
    private final OutputStream outboundLogStream;
    private final OutputStream inboundLogStream;

    /**
     * Create a connection. The underlying socket channel can be either
     * blocking or non-blocking.
     *
     * @param clock the clock
     * @param channel the underlying socket channel
     * @param config the connection configuration
     * @param listener the inbound message listener
     * @param statusListener the inbound status event listener
     */
    public FIXConnection(Clock clock, SocketChannel channel, FIXConfig config, FIXMessageListener listener,
            FIXConnectionStatusListener statusListener, OutputStream outboundLogStream, OutputStream inboundLogStream) {
        this.clock = clock;

        this.channel = channel;

        this.config = config;

        this.bodyLength = new FIXValue(BODY_LENGTH_FIELD_CAPACITY);
        this.checkSum   = new FIXValue(CHECK_SUM_FIELD_CAPACITY);

        this.senderCompId = config.getSenderCompID();
        this.targetCompId = config.getTargetCompID();
        
        this.outboundLogStream = outboundLogStream;
        this.inboundLogStream = inboundLogStream;
        MessageInboundLogger inboundLogger = inboundLogStream==null ? null : new MessageInboundLogger(inboundLogStream);

        this.parser = new FIXMessageParser(config, new MessageHandler(listener, inboundLogger));

        this.statusListener = statusListener;

        this.rxMsgSeqNum = config.getIncomingMsgSeqNum();
        this.txMsgSeqNum = config.getOutgoingMsgSeqNum();

        this.rxBuffer = ByteBuffer.allocateDirect(config.getRxBufferCapacity());

        this.txHeaderBuffer = ByteBuffer.allocateDirect(config.getTxBufferCapacity());

        beginString = new FIXValue(BEGIN_STRING_FIELD_CAPACITY);
        beginString.setString(config.getVersion().getBeginString());
        
        bodyLengthValue = new FIXValue(BODY_LENGTH_FIELD_CAPACITY);

        this.txHeaderBuffer.put(BEGIN_STRING);
        beginString.put(this.txHeaderBuffer);
        this.txHeaderBuffer.put(BODY_LENGTH);

        this.bodyLengthOffset = this.txHeaderBuffer.position();

        this.txBodyBuffer = ByteBuffer.allocateDirect(config.getTxBufferCapacity());

        this.txBuffers = new ByteBuffer[2];

        this.txBuffers[0] = txHeaderBuffer;
        this.txBuffers[1] = txBodyBuffer;

        this.lastRxMillis = clock.currentTimeMillis();
        this.lastTxMillis = clock.currentTimeMillis();

        this.heartbeatMillis = config.getHeartBtInt() * 1000;

        this.testRequestMillis = config.getHeartBtInt() * 1100;

        this.testRequestTxMillis = 0;

        this.txMessage = new FIXMessage(config.getMaxFieldCount(), config.getFieldCapacity());

        this.currentTimeMillis = clock.currentTimeMillis();

        this.currentTime = new MutableDateTime(this.currentTimeMillis, DateTimeZone.UTC);

        this.currentTimestamp = new StringBuilder(config.getFieldCapacity());

        FIXTimestamps.append(this.currentTime, this.currentTimestamp);
                
     }

    /**
     * Create a connection. The underlying socket channel can be either
     * blocking or non-blocking.
     *
     * @param clock the clock
     * @param channel the underlying socket channel
     * @param config the connection configuration
     * @param listener the inbound message listener
     * @param statusListener the inbound status event listener
     */
    public FIXConnection(Clock clock, SocketChannel channel, FIXConfig config, FIXMessageListener listener,
            FIXConnectionStatusListener statusListener) {
    	this(clock, channel, config, listener, statusListener, null, null);
    }

    /**
     * Create a connection. The underlying socket channel can be either
     * blocking or non-blocking.
     *
     * @param channel the underlying socket channel
     * @param config the connection configuration
     * @param listener the inbound message listener
     * @param statusListener the inbound status event listener
     */
    public FIXConnection(SocketChannel channel, FIXConfig config, FIXMessageListener listener,
            FIXConnectionStatusListener statusListener) {
        this(System::currentTimeMillis, channel, config, listener, statusListener, null, null);
    }

    /**
     * Get the underlying socket channel.
     *
     * @return the underlying socket channel
     */
    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * Get the next incoming MsgSeqNum(34).
     *
     * @return the next incoming MsgSeqNum(34)
     */
    public long getIncomingMsgSeqNum() {
        return rxMsgSeqNum;
    }

    void setIncomingMsgSeqNum(long incomingMsgSeqNum) {
        rxMsgSeqNum = incomingMsgSeqNum;
    }

    /**
     * Get the next outgoing MsgSeqNum(34).
     *
     * @return the next outgoing MsgSeqNum(34)
     */
    public long getOutgoingMsgSeqNum() {
        return txMsgSeqNum;
    }

    /**
     * Get the SenderCompID(49).
     *
     * @return the SenderCompID(49)
     */
    public String getSenderCompID() {
        return senderCompId;
    }

    /**
     * Get the TargetCompID(56).
     *
     * @return the TargetCompID(56)
     */
    public String getTargetCompID() {
        return targetCompId;
    }

    /**
     * Create a message container.
     *
     * @return a message container
     */
    public FIXMessage create() {
        return new FIXMessage(config.getMaxFieldCount(), config.getFieldCapacity());
    }

    /**
     * <p>Prepare a message. When preparing a message, the following mandatory
     * fields are added:</p>
     *
     * <ul>
     *   <li>MsgType(35)</li>
     *   <li>SenderCompID(49)</li>
     *   <li>TargetCompID(56)</li>
     *   <li>MsgSeqNum(34)</li>
     *   <li>SendingTime(52)</li>
     * </ul>
     *
     * @param message a message
     * @param msgType the MsgType(35)
     */
    public void prepare(FIXMessage message, char msgType) {
        message.reset();

        message.addField(MsgType).setChar(msgType);

        prepare(message);
    }

    /**
     * <p>Prepare a message.</p>
     *
     * @param message a message
     * @param msgType the MsgType(35)
     * @see #prepare(FIXMessage, char)
     */
    public void prepare(FIXMessage message, CharSequence msgType) {
        message.reset();

        message.addField(MsgType).setString(msgType);

        prepare(message);
    }

    private void prepare(FIXMessage message) {
        message.addField(SenderCompID).setString(senderCompId);
        message.addField(TargetCompID).setString(targetCompId);
        message.addField(MsgSeqNum).setInt(txMsgSeqNum);
        message.addField(SendingTime).setString(currentTimestamp);
    }

    /**
     * <p>Update a message. When updating a message, the following mandatory
     * fields are updated:</p>
     *
     * <ul>
     *   <li>MsgSeqNum(34)</li>
     *   <li>SendingTime(52)</li>
     * </ul>
     *
     * @param message a message
     * @throws NullPointerException if MsgSeqNum(34) or SendingTime(52) is
     *   not found
     */
    public void update(FIXMessage message) {
        message.valueOf(MsgSeqNum).setInt(txMsgSeqNum);
        message.valueOf(SendingTime).setString(currentTimestamp);
    }

    /**
     * Update SenderCompID(49) and TargetCompID(56).
     *
     * @param message a message
     * @throws NullPointerException if SenderCompID(49) or TargetCompID(56)
     *   is not found
     */
    public void updateCompID(FIXMessage message) {
        message.valueOf(SenderCompID).setString(senderCompId);
        message.valueOf(TargetCompID).setString(targetCompId);
    }

    /**
     * <p>Update the current timestamp. The current timestamp is used for the
     * following purposes:</p>
     *
     * <ul>
     *   <li>SendingTime(52)</li>
     *   <li>the connection keep-alive mechanism</li>
     * </ul>
     */
    public void updateCurrentTimestamp() {
        currentTimeMillis = clock.currentTimeMillis();

        currentTime.setMillis(currentTimeMillis);

        currentTimestamp.setLength(0);

        FIXTimestamps.append(currentTime, currentTimestamp);
    }

    /**
     * Get the current timestamp.
     *
     * @return the current timestamp
     */
    public CharSequence getCurrentTimestamp() {
        return currentTimestamp;
    }

    /**
     * Keep this connection alive.
     *
     * <p>If the duration indicated by HeartBtInt(108) has passed since
     * sending a message, send a Heartbeat(0) message.</p>
     *
     * <p>If the duration indicated by HeartBtInt(108) amended with a
     * reasonable transmission time has passed since receiving a message,
     * send a TestRequest(1) message.</p>
     *
     * <p>If a TestRequest(1) message has been sent and no data has been
     * received within the duration indicated by HeartBtInt(108) amended with
     * a reasonable transmission time, trigger a status event indicating
     * heartbeat timeout.</p>
     *
     * @throws IOException if an I/O error occurs
     */
    public void keepAlive() throws IOException {
        if (currentTimeMillis - lastTxMillis > heartbeatMillis)
            sendHeartbeat();

        if (testRequestTxMillis == 0) {
            if (currentTimeMillis - lastRxMillis > testRequestMillis) {
                sendTestRequest(currentTimestamp);

                testRequestTxMillis = currentTimeMillis;
            }
        } else {
            if (currentTimeMillis - testRequestTxMillis > testRequestMillis) {
                statusListener.heartbeatTimeout(this);

                testRequestTxMillis = 0;
            }
        }
    }

    /**
     * Close the underlying socket channel.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        channel.close();
        System.out.println("FIXConnect close()");
        if (inboundLogStream != null) {
        	inboundLogStream.close();
        }
        if (outboundLogStream != null && inboundLogStream != outboundLogStream ) {
        	outboundLogStream.close();
        }
    }

    /**
     * Receive data from the underlying socket channel. For each message
     * received, invoke the message listener if applicable.
     *
     * @return the number of bytes read, possibly zero, or -1 if the channel
     *   has reached end-of-stream
     * @throws IOException if an I/O error occurs
     */
    public int receive() throws IOException {
        int bytes = channel.read(rxBuffer);

        if (bytes <= 0)
            return bytes;

        rxBuffer.flip();

        while (parser.parse(rxBuffer));

        rxBuffer.compact();

        if (rxBuffer.position() == rxBuffer.capacity())
            tooLongMessage();

        lastRxMillis = currentTimeMillis;

        testRequestTxMillis = 0;

        return bytes;
    }

    /**
     * Send a message.
     *
     * @param message a message
     * @throws IOException if an I/O error occurs
     */
    public void send(FIXMessage message) throws IOException {
        txBodyBuffer.clear();
        message.put(txBodyBuffer);

        bodyLength.setInt(txBodyBuffer.position());

        txHeaderBuffer.position(bodyLengthOffset);
        bodyLength.put(txHeaderBuffer);

        checkSum.setCheckSum(FIXCheckSums.sum(txHeaderBuffer, 0, txHeaderBuffer.position()) +
                FIXCheckSums.sum(txBodyBuffer, 0, txBodyBuffer.position()));

        txBodyBuffer.put(CHECK_SUM);
        checkSum.put(txBodyBuffer);

        if (outboundLogStream != null) {
        	outputLogByteBuffer.clear();
            txHeaderBuffer.flip();
            txBodyBuffer.flip();
            outputLogByteBuffer.put(txHeaderBuffer);
            outputLogByteBuffer.put(txBodyBuffer);
            outboundLogStream.write(outputLogByteBuffer.array(), 0, outputLogByteBuffer.position());
            outboundLogStream.write('\n');
            outboundLogStream.flush();
	    }
        
        txHeaderBuffer.flip();
        txBodyBuffer.flip();

        int remaining = txHeaderBuffer.remaining() + txBodyBuffer.remaining();

        do {
            remaining -= channel.write(txBuffers, 0, txBuffers.length);
        } while (remaining > 0);

        txMsgSeqNum++;

        lastTxMillis = currentTimeMillis;
    }

    /**
     * Send a Reject(3) message.
     *
     * @param refSeqNum the RefSeqNum(45)
     * @param sessionRejectReason the SessionRejectReason(373)
     * @param text the Text(58)
     * @throws IOException if an I/O error occurs
     */
    public void sendReject(long refSeqNum, long sessionRejectReason, CharSequence text) throws IOException {
        prepare(txMessage, Reject);

        txMessage.addField(RefSeqNum).setInt(refSeqNum);
        txMessage.addField(SessionRejectReason).setInt(sessionRejectReason);
        txMessage.addField(Text).setString(text);

        send(txMessage);
    }

    /**
     * Send a Logout(5) message.
     *
     * @throws IOException if an I/O error occurs
     */
    public void sendLogout() throws IOException {
        prepare(txMessage, Logout);

        send(txMessage);
    }

    /**
     * Send a Logout(5) message.
     *
     * @param text the Text(58)
     * @throws IOException if an I/O error occurs
     */
    public void sendLogout(CharSequence text) throws IOException {
        prepare(txMessage, Logout);

        txMessage.addField(Text).setString(text);

        send(txMessage);
    }

    /**
     * Send a Logon(A) message. Set EncryptMethod(98) to 0 and HeartBtInt(108)
     * according to the connection configuration.
     *
     * @param resetSeqNum if true set ResetSeqNumFlag(141) to true, otherwise
     *   omit ResetSeqNumFlag(141)
     * @throws IOException if an I/O error occurs
     */
    public void sendLogon(boolean resetSeqNum) throws IOException {
        prepare(txMessage, Logon);

        txMessage.addField(EncryptMethod).setInt(0);
        txMessage.addField(HeartBtInt).setInt(config.getHeartBtInt());

        if (resetSeqNum)
            txMessage.addField(ResetSeqNumFlag).setBoolean(true);

        send(txMessage);
    }

    /**
     * A second FIXMessageListener used for logging inbound messages. 
     * 
     * TODO it would be more efficient to copy these bytes from the input stream in FIXMessageParser, but it wasn't obvious how to do that.
     * 
     * @author reasterling
     */
    private class MessageInboundLogger implements FIXMessageListener {

    	private final OutputStream inboundLogs;
        private byte[] inputLogBuffer = new byte[FIX_BUFFER_SIZE];
        private ByteBuffer inputLogByteBuffer = ByteBuffer.wrap(inputLogBuffer);
    	
    	public MessageInboundLogger(OutputStream inboundLogs) {
        	this.inboundLogs = inboundLogs;
    	}
    	
		@Override
		public void message(FIXMessage message) throws IOException {
	       	inputLogByteBuffer.clear();
	       	//need to put the FIX version and a dummy BodyLength value to get the log reader to work
	       	FIXTags.put(inputLogByteBuffer, FIXTags.BeginString); beginString.put(inputLogByteBuffer);
	       	bodyLengthValue.setInt(100); //TODO the actual length is buried in FIXMessageParser
	       	FIXTags.put(inputLogByteBuffer, FIXTags.BodyLength); bodyLengthValue.put(inputLogByteBuffer);
           
	       	message.put(inputLogByteBuffer);
	       	inboundLogs.write(inputLogByteBuffer.array(), 0, inputLogByteBuffer.position());
	       	inboundLogs.write('\n');
	       	inboundLogs.flush();
		}
    }

    private class MessageHandler implements FIXMessageListener {

        private FIXMessageListener downstream;
        private FIXMessageListener inboundLogger;

        MessageHandler(FIXMessageListener downstream, FIXMessageListener inboundLogger) {
            this.downstream = downstream;
            this.inboundLogger = inboundLogger;
        }

//        MessageHandler(FIXMessageListener downstream) {
//            this(downstream, null);
//        }

        @Override
        public void message(FIXMessage message) throws IOException {
            long msgSeqNum = message.getMsgSeqNum();
            if (msgSeqNum == 0) {
                msgSeqNumNotFound();
                return;
            }

            FIXValue msgType = message.getMsgType();
            if (msgType == null) {
                msgTypeNotFound();
                return;
            }
            
            if (inboundLogger != null) {
            	inboundLogger.message(message);
            }

            if (msgType.length() == 1 && msgType.byteAt(0) == SequenceReset) {
                if (handleSequenceReset(message))
                    return;
            }

            if (msgSeqNum != rxMsgSeqNum) {
                handleMsgSeqNum(message, msgType, msgSeqNum);
                return;
            }

            //rxMsgSeqNum++;
            if (msgSeqNum > rxMsgSeqNum) { 
            	rxMsgSeqNum = msgSeqNum; 
            } else { 
            	rxMsgSeqNum++; 
            }

            if (msgType.length() != 1) {
                downstream.message(message);
                return;
            }

            switch (msgType.byteAt(0)) {
            case Heartbeat:
                break;
            case TestRequest:
                handleTestRequest(message);
                break;
            case ResendRequest:
                handleResendRequest(message);
                break;
            case Reject:
                handleReject(message);
                break;
            case SequenceReset:
                handleSequenceReset(message);
                break;
            case Logout:
                handleLogout(message);
                break;
            case Logon:
                handleLogon(message);
                break;
            default:
                downstream.message(message);
                break;
            }
        }

        private void handleMsgSeqNum(FIXMessage message, FIXValue msgType, long msgSeqNum) throws IOException {
            if (msgSeqNum < rxMsgSeqNum)
                handleTooLowMsgSeqNum(message, msgType, msgSeqNum);
            else
                sendResendRequest(rxMsgSeqNum);
        }

        private void handleTooLowMsgSeqNum(FIXMessage message, FIXValue msgType, long msgSeqNum) throws IOException {
            if (msgType.length() != 1 || msgType.asChar() != SequenceReset) {
                FIXValue possDupFlag = message.valueOf(PossDupFlag);

                if (possDupFlag == null || !possDupFlag.asBoolean())
                    statusListener.tooLowMsgSeqNum(FIXConnection.this, msgSeqNum, rxMsgSeqNum);
            }
        }

        private void handleTestRequest(FIXMessage message) throws IOException {
            FIXValue testReqId = message.valueOf(TestReqID);
            if (testReqId == null) {
                sendReject(message.getMsgSeqNum(), RequiredTagMissing, "TestReqID(112) not found");
                return;
            }

            sendHeartbeat(testReqId);
        }

        private void handleResendRequest(FIXMessage message) throws IOException {
            FIXValue beginSeqNo = message.valueOf(BeginSeqNo);
            if (beginSeqNo == null) {
                sendReject(message.getMsgSeqNum(), RequiredTagMissing, "BeginSeqNo(7) not found");
                return;
            }

            FIXValue endSeqNo = message.valueOf(EndSeqNo);
            if (endSeqNo == null) {
                sendReject(message.getMsgSeqNum(), RequiredTagMissing, "EndSeqNo(16) not found");
                return;
            }

            sendSequenceReset(beginSeqNo, endSeqNo.asInt() + 1);
        }

        private void handleReject(FIXMessage message) throws IOException {
            statusListener.reject(FIXConnection.this, message);
        }

        private boolean handleSequenceReset(FIXMessage message) throws IOException {
            FIXValue value = message.valueOf(NewSeqNo);
            if (value == null) {
                sendReject(message.getMsgSeqNum(), RequiredTagMissing, "NewSeqNo(36) not found");
                return true;
            }

            long newSeqNo = value.asInt();
            if (newSeqNo < rxMsgSeqNum) {
                sendReject(message.getMsgSeqNum(), ValueIsIncorrect, "NewSeqNo(36) too low");
                return true;
            }

            rxMsgSeqNum = newSeqNo;

            FIXValue gapFillFlag = message.valueOf(GapFillFlag);
            boolean reset = gapFillFlag == null || !gapFillFlag.asBoolean();

            if (reset)
                statusListener.sequenceReset(FIXConnection.this);

            return reset;
        }

        private void handleLogout(FIXMessage message) throws IOException {
            statusListener.logout(FIXConnection.this, message);
        }

        private void handleLogon(FIXMessage message) throws IOException {
            if (senderCompId.isEmpty()) {
                FIXValue value = message.valueOf(TargetCompID);
                if (value == null) {
                    statusListener.close(FIXConnection.this, "SenderCompID(49) not found");
                    return;
                }

                senderCompId = value.asString();
            }

            if (targetCompId.isEmpty()) {
                FIXValue value = message.valueOf(SenderCompID);
                if (value == null) {
                    statusListener.close(FIXConnection.this, "TargetCompID(56) not found");
                    return;
                }

                targetCompId = value.asString();
            }

            statusListener.logon(FIXConnection.this, message);
        }

        private void sendHeartbeat(FIXValue testReqId) throws IOException {
            prepare(txMessage, Heartbeat);

            txMessage.addField(TestReqID).set(testReqId);

            send(txMessage);
        }

        private void sendResendRequest(long beginSeqNo) throws IOException {
            prepare(txMessage, ResendRequest);

            txMessage.addField(BeginSeqNo).setInt(beginSeqNo);
            txMessage.addField(EndSeqNo).setInt(0);

            send(txMessage);
        }

        private void sendSequenceReset(FIXValue msgSeqNum, long newSeqNo) throws IOException {
            prepare(txMessage, SequenceReset);

            txMessage.valueOf(MsgSeqNum).set(msgSeqNum);
            txMessage.addField(GapFillFlag).setBoolean(true);
            txMessage.addField(NewSeqNo).setInt(newSeqNo);

            send(txMessage);
        }

        private void msgSeqNumNotFound() throws IOException {
            sendLogout("MsgSeqNum(34) not found");
        }

        private void msgTypeNotFound() throws IOException {
            statusListener.close(FIXConnection.this, "MsgType(35) not found");
        }

    }

    private void sendHeartbeat() throws IOException {
        prepare(txMessage, Heartbeat);

        send(txMessage);
    }

    private void sendTestRequest(CharSequence testReqId) throws IOException {
        prepare(txMessage, TestRequest);

        txMessage.addField(TestReqID).setString(testReqId);

        send(txMessage);
    }

    private static void tooLongMessage() throws FIXMessageOverflowException {
        throw new FIXMessageOverflowException("Too long message");
    }

}
