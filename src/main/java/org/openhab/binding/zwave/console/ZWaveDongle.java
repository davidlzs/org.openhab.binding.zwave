package org.openhab.binding.zwave.console;

import gnu.io.*;
import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.eclipse.smarthome.core.events.Event;
import org.eclipse.smarthome.core.events.EventPublisher;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.openhab.binding.zwave.ZWaveBindingConstants;
import org.openhab.binding.zwave.event.BindingEventDTO;
import org.openhab.binding.zwave.event.BindingEventFactory;
import org.openhab.binding.zwave.event.BindingEventType;
import org.openhab.binding.zwave.handler.ZWaveSerialHandler;
import org.openhab.binding.zwave.internal.ZWaveEventPublisher;
import org.openhab.binding.zwave.internal.protocol.*;
import org.openhab.binding.zwave.internal.protocol.event.*;
import org.openhab.binding.zwave.internal.protocol.serialmessage.RemoveFailedNodeMessageClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TooManyListenersException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ZWaveDongle implements ZWaveEventListener, ZWaveIoHandler {
    private Logger logger = LoggerFactory.getLogger(ZWaveSerialHandler.class);
    private final String portId;
    private SerialPort serialPort;

    private int SOFCount = 0;
    private int CANCount = 0;
    private int NAKCount = 0;
    private int ACKCount = 0;
    private int OOFCount = 0;
    private int CSECount = 0;

    private ZWaveReceiveThread receiveThread;
    private static final int SERIAL_RECEIVE_TIMEOUT = 250;

    private Boolean isMaster = true;
    private Integer sucNode = 0;
    private String networkKey = "";
    private Integer secureInclusionMode = 0;
    private Integer healTime = -1;
    private Integer wakeupDefaultPeriod = 0;


    private int searchTime;
    private final int SEARCHTIME_MINIMUM = 20;
    private final int SEARCHTIME_DEFAULT = 30;
    private final int SEARCHTIME_MAXIMUM = 300;

    private volatile ZWaveController controller;

    protected final ScheduledExecutorService scheduler = ThreadPoolManager.getScheduledPool("thingHandler");
    private ScheduledFuture<?> healJob = null;


    public ZWaveDongle(String portId) {
        this.portId = portId;
    }

    public void initialize() {
        logger.debug("Initializing ZWave serial controller.");


        if (portId == null || portId.length() == 0) {
            logger.error("ZWave port is not set.");
            return;
        }

        superInitialize();
        logger.info("Connecting to serial port '{}'", portId);
        try {
            CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(portId);
            CommPort commPort = portIdentifier.open("org.openhab.binding.zwave", 2000);
            serialPort = (SerialPort) commPort;
            serialPort.setSerialPortParams(115200, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);
            serialPort.enableReceiveThreshold(1);
            serialPort.enableReceiveTimeout(SERIAL_RECEIVE_TIMEOUT);
            logger.debug("Starting receive thread");
            receiveThread = new ZWaveReceiveThread();
            receiveThread.start();

            // RXTX serial port library causes high CPU load
            // Start event listener, which will just sleep and slow down event loop
            serialPort.addEventListener(receiveThread);
            serialPort.notifyOnDataAvailable(true);

            logger.info("Serial port is initialized");

            initializeNetwork();
        } catch (NoSuchPortException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR);
        } catch (PortInUseException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR);
        } catch (UnsupportedCommOperationException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR);
        } catch (TooManyListenersException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR);
        }
    }

    private void updateStatus(ThingStatus status, ThingStatusDetail statusDetails) {
        logger.info("Thing Status: {}", status);
    }


    public void setMaster(Boolean master) {
        isMaster = master;
    }

    public void setSecureInclusionMode(Integer secureInclusionMode) {
        this.secureInclusionMode = secureInclusionMode;
    }

    public void setWakeupDefaultPeriod(Integer wakeupDefaultPeriod) {
        this.wakeupDefaultPeriod = wakeupDefaultPeriod;
    }

    public void setSearchTime(int searchTime) {
        this.searchTime = searchTime;
    }

    public void setSucNode(Integer sucNode) {
        this.sucNode = sucNode;
    }

    public void setNetworkKey(String networkKey) {
        this.networkKey = networkKey;
    }

    public void setHealTime(Integer healTime) {
        this.healTime = healTime;
    }

    public void superInitialize() {

        Object param;

        if (searchTime < SEARCHTIME_MINIMUM || searchTime > SEARCHTIME_MAXIMUM) {
            searchTime = SEARCHTIME_DEFAULT;
        }

        if (networkKey.length() == 0) {
            logger.debug("No network key set by user - using random value.");

            // Create random network key
            networkKey = "";
            for (int cnt = 0; cnt < 16; cnt++) {
                int value = (int) Math.floor((Math.random() * 255));
                if (cnt != 0) {
                    networkKey += " ";
                }
                networkKey += String.format("%02X", value);
            }
        }


        initializeHeal();

    }



    private void initializeHeal() {
        if (healJob != null) {
            healJob.cancel(true);
            healJob = null;
        }

        if (healTime >= 0 && healTime <= 23) {
            Runnable healRunnable = new Runnable() {
                @Override
                public void run() {
                    if (controller == null) {
                        return;
                    }
                    logger.debug("Starting network mesh heal for controller");
                    for (ZWaveNode node : controller.getNodes()) {
                        logger.debug("Starting network mesh heal for controller");
                        node.healNode();
                    }
                }
            };

            Calendar cal = Calendar.getInstance();
            int hours = healTime - cal.get(Calendar.HOUR_OF_DAY);
            if (hours < 0) {
                hours += 24;
            }

            logger.debug("Scheduling network mesh heal for {} hours time.", hours);

            healJob = scheduler.scheduleAtFixedRate(healRunnable, hours, 24, TimeUnit.HOURS);
        }
    }

    /**
     * Common initialisation point for all ZWave controllers.
     * Called by bridges after they have initialised their interfaces.
     *
     */
    protected void initializeNetwork() {
        logger.debug("Initialising ZWave controller");

        // Create config parameters
        Map<String, String> config = new HashMap<String, String>();
        config.put("masterController", isMaster.toString());
        config.put("sucNode", sucNode.toString());
        config.put("secureInclusion", secureInclusionMode.toString());
        config.put("networkKey", networkKey);
        config.put("wakeupDefaultPeriod", wakeupDefaultPeriod.toString());

        // TODO: Handle soft reset?
        controller = new ZWaveController(this, config);
        controller.addEventListener(this);

    }


    protected void incomingMessage(SerialMessage serialMessage) {
        if (controller == null) {
            return;
        }
        controller.incomingPacket(serialMessage);
    }

    public int getOwnNodeId() {
        if (controller == null) {
            return 0;
        }
        return controller.getOwnNodeId();
    }

    public ZWaveNode getNode(int node) {
        if (controller == null) {
            return null;
        }

        return controller.getNode(node);
    }

    private void updateNeighbours() {
        if (controller == null) {
            return;
        }

        ZWaveNode node = getNode(getOwnNodeId());
        if (node == null) {
            return;
        }

        String neighbours = "";
        for (Integer neighbour : node.getNeighbors()) {
            if (neighbours.length() != 0) {
                neighbours += ',';
            }
            neighbours += neighbour;
        }
    }

    @Override
    public void ZWaveIncomingEvent(ZWaveEvent event) {
        // If this event requires us to let the users know something, then we create a notification
        String eventKey = null;
        BindingEventType eventState = null;
        String eventEntity = null;
        String eventId = null;
        Object eventArgs = null;

        if (event instanceof ZWaveNetworkStateEvent) {
            logger.debug("Controller: Incoming Network State Event {}",
                    ((ZWaveNetworkStateEvent) event).getNetworkState());
        }

        if (event instanceof ZWaveNetworkEvent) {
            ZWaveNetworkEvent networkEvent = (ZWaveNetworkEvent) event;

            switch (networkEvent.getEvent()) {
                case NodeRoutingInfo:
                    if (networkEvent.getNodeId() == getOwnNodeId()) {
                        updateNeighbours();
                    }
                    break;
                case RemoveFailedNodeID:
                    eventEntity = "network"; // ??
                    eventArgs = new Integer(networkEvent.getNodeId());
                    eventId = ((RemoveFailedNodeMessageClass.Report) networkEvent.getValue()).toString();
                    switch ((RemoveFailedNodeMessageClass.Report) networkEvent.getValue()) {
                        case FAILED_NODE_NOT_FOUND:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTFOUND;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_PRIMARY_CONTROLLER:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTCTLR;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTREMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NO_CALLBACK_FUNCTION:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOCALLBACK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_OK:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NODEOK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_REMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_FAILED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_PROCESS_BUSY:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_BUSY;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_UNKNOWN_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_UNKNOWN;
                            eventState = BindingEventType.WARNING;
                            break;
                        default:
                            break;
                    }
                    break;
                case ReplaceFailedNode:
                    eventEntity = "network"; // ??
                    eventArgs = new Integer(networkEvent.getNodeId());
                    eventId = ((RemoveFailedNodeMessageClass.Report) networkEvent.getValue()).toString();
                    switch ((RemoveFailedNodeMessageClass.Report) networkEvent.getValue()) {
                        case FAILED_NODE_NOT_FOUND:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTFOUND;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_PRIMARY_CONTROLLER:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTCTLR;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTREMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NO_CALLBACK_FUNCTION:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOCALLBACK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_OK:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NODEOK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_REMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_FAILED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_PROCESS_BUSY:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_BUSY;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_UNKNOWN_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_UNKNOWN;
                            eventState = BindingEventType.WARNING;
                            break;
                        default:
                            break;
                    }
                    break;
                case RequestNetworkUpdate:
                    eventEntity = "network";

                    switch ((int) networkEvent.getValue()) {
                        case 0: // ZW_SUC_UPDATE_DONE
                            eventId = "ZW_SUC_UPDATE_DONE";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_DONE;
                            eventState = BindingEventType.SUCCESS;
                            break;
                        case 1: // ZW_SUC_UPDATE_ABORT
                            eventId = "ZW_SUC_UPDATE_ABORT";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_ABORT;
                            eventState = BindingEventType.WARNING;
                            break;
                        case 2: // ZW_SUC_UPDATE_WAIT
                            eventId = "ZW_SUC_UPDATE_WAIT";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_WAIT;
                            eventState = BindingEventType.WARNING;
                            break;
                        case 3: // ZW_SUC_UPDATE_DISABLED
                            eventId = "ZW_SUC_UPDATE_DISABLED";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_DISABLED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case 4: // ZW_SUC_UPDATE_OVERFLOW
                            eventId = "ZW_SUC_UPDATE_OVERFLOW";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_OVERFLOW;
                            eventState = BindingEventType.WARNING;
                            break;
                        default:
                            break;
                    }
                    break;
                default:
                    break;
            }
        }

        // Handle node discover inclusion events
        if (event instanceof ZWaveInclusionEvent) {
            ZWaveInclusionEvent incEvent = (ZWaveInclusionEvent) event;

            eventEntity = "network";
            eventId = incEvent.getEvent().toString();
            switch (incEvent.getEvent()) {
                case IncludeStart:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_STARTED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case IncludeFail:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_FAILED;
                    eventState = BindingEventType.WARNING;
                    break;
                case IncludeDone:
                    // Ignore node 0 - this just indicates inclusion is finished
                    if (incEvent.getNodeId() == 0) {
                        break;
                    }
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_COMPLETED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case SecureIncludeComplete:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_SECURECOMPLETED;
                    eventState = BindingEventType.SUCCESS;
                    eventArgs = new Integer(incEvent.getNodeId());
                    break;
                case SecureIncludeFailed:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_SECUREFAILED;
                    eventState = BindingEventType.ERROR;
                    eventArgs = new Integer(incEvent.getNodeId());
                    break;
                case ExcludeStart:
                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_STARTED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case ExcludeFail:
                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_FAILED;
                    eventState = BindingEventType.WARNING;
                    break;
                case ExcludeDone:
                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_COMPLETED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case ExcludeControllerFound:
                case ExcludeSlaveFound:
                    // Ignore node 0 - this just indicates exclusion finished
                    if (incEvent.getNodeId() == 0) {
                        break;
                    }

                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_NODEREMOVED;
                    eventState = BindingEventType.SUCCESS;
                    eventArgs = new Integer(incEvent.getNodeId());
                    break;
                case IncludeControllerFound:
                case IncludeSlaveFound:
                    break;
            }
        }

        if (event instanceof ZWaveInitializationStateEvent) {
            ZWaveInitializationStateEvent initEvent = (ZWaveInitializationStateEvent) event;
            switch (initEvent.getStage()) {
                case DISCOVERY_COMPLETE:
                    // At this point we know enough information about the device to advise the discovery
                    // service that there's a new thing.
                    // We need to do this here as we needed to know the device information such as manufacturer,
                    // type, id and version
                    ZWaveNode node = controller.getNode(initEvent.getNodeId());
                    if (node != null) {
                        logger.info("{} discovered", node);
                    }
                default:
                    break;
            }
        }

        if (eventKey != null) {
            EventPublisher ep = ZWaveEventPublisher.getEventPublisher();
            if (ep != null) {
                BindingEventDTO dto = new BindingEventDTO(eventState,
                        BindingEventFactory.formatEvent(eventKey, eventArgs));
                Event notification = BindingEventFactory.createBindingEvent(ZWaveBindingConstants.BINDING_ID,
                        eventEntity, eventId, dto);
                ep.post(notification);
            }
        }
    }


    /**
     * ZWave controller Receive Thread. Takes care of receiving all messages.
     * It uses a semaphore to synchronize communication with the sending thread.
     */
    private class ZWaveReceiveThread extends Thread implements SerialPortEventListener {

        private final int SEARCH_SOF = 0;
        private final int SEARCH_LEN = 1;
        private final int SEARCH_DAT = 2;

        private int rxState = SEARCH_SOF;
        private int messageLength;
        private int rxLength;
        private byte[] rxBuffer;

        private static final int SOF = 0x01;
        private static final int ACK = 0x06;
        private static final int NAK = 0x15;
        private static final int CAN = 0x18;

        private final Logger logger = LoggerFactory.getLogger(ZWaveDongle.ZWaveReceiveThread.class);

        ZWaveReceiveThread() {
            super("ZWaveReceiveInputThread");
        }

        @Override
        public void serialEvent(SerialPortEvent arg0) {
            try {
                logger.trace("RXTX library CPU load workaround, sleep forever");
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
            }
        }

        /**
         * Sends 1 byte frame response.
         *
         * @param response the response code to send.
         */
        private void sendResponse(int response) {
            try {
                synchronized (serialPort.getOutputStream()) {
                    serialPort.getOutputStream().write(response);
                    serialPort.getOutputStream().flush();
                    logger.trace("Response SENT {}", response);
                }
            } catch (IOException e) {
                logger.error("Exception during send", e);
            }
        }

        /**
         * Run method. Runs the actual receiving process.
         */
        @Override
        public void run() {
            logger.debug("Starting ZWave thread: Receive");
            try {

                // Send a NAK to resynchronise communications
                sendResponse(NAK);

                while (!interrupted()) {
                    int nextByte;

                    try {
                        nextByte = serialPort.getInputStream().read();
                        // logger.debug("SERIAL:: STATE {}, nextByte {}, count {} ", rxState, nextByte, rxLength);

                        // If byte value is -1, this is a timeout
                        if (nextByte == -1) {
                            if (rxState != SEARCH_SOF) {
                                // If we're not searching for a new frame when we get a timeout, something bad happened
                                logger.debug("Receive Timeout - Sending NAK");
                                rxState = SEARCH_SOF;
                            }
                            continue;
                        }
                    } catch (IOException e) {
                        logger.error("Got I/O exception {} during receiving. exiting thread.", e.getLocalizedMessage());
                        break;
                    }

                    switch (rxState) {
                        case SEARCH_SOF:
                            switch (nextByte) {
                                case SOF:
                                    logger.trace("Received SOF");

                                    // Keep track of statistics
                                    SOFCount++;
                                    rxState = SEARCH_LEN;
                                    break;

                                case ACK:
                                    // Keep track of statistics
                                    ACKCount++;
                                    logger.debug("Receive Message = 06");
                                    SerialMessage ackMessage = new SerialMessage(new byte[]{ACK});
                                    incomingMessage(ackMessage);
                                    break;

                                case NAK:
                                    // A NAK means the CRC was incorrectly received by the controller
                                    NAKCount++;
                                    logger.debug("Receive Message = 15");
                                    SerialMessage nakMessage = new SerialMessage(new byte[]{NAK});
                                    incomingMessage(nakMessage);
                                    break;

                                case CAN:
                                    // The CAN means that the controller dropped the frame
                                    CANCount++;
                                    // logger.debug("Protocol error (CAN)");
                                    logger.debug("Receive Message = 18");
                                    SerialMessage canMessage = new SerialMessage(new byte[]{CAN});
                                    incomingMessage(canMessage);
                                    break;

                                default:
                                    OOFCount++;
                                    logger.debug(String.format("Protocol error (OOF). Got 0x%02X.", nextByte));
                                    // Let the timeout deal with sending the NAK
                                    break;
                            }
                            break;

                        case SEARCH_LEN:
                            // Sanity check the frame length
                            if (nextByte < 4 || nextByte > 64) {
                                logger.debug("Frame length is out of limits ({})", nextByte);

                                break;
                            }
                            messageLength = (nextByte & 0xff) + 2;

                            rxBuffer = new byte[messageLength];
                            rxBuffer[0] = SOF;
                            rxBuffer[1] = (byte) nextByte;
                            rxLength = 2;
                            rxState = SEARCH_DAT;
                            break;

                        case SEARCH_DAT:
                            rxBuffer[rxLength] = (byte) nextByte;
                            rxLength++;

                            if (rxLength < messageLength) {
                                break;
                            }

                            logger.debug("Receive Message = {}", SerialMessage.bb2hex(rxBuffer));
                            SerialMessage recvMessage = new SerialMessage(rxBuffer);
                            if (recvMessage.isValid) {
                                logger.trace("Message is valid, sending ACK");
                                sendResponse(ACK);

                                incomingMessage(recvMessage);
                            } else {
                                CSECount++;

                                logger.debug("Message is invalid, discarding");
                                sendResponse(NAK);
                            }

                            rxState = SEARCH_SOF;
                            break;
                    }

                }
            } catch (Exception e) {
                logger.error("Exception during ZWave thread. ", e);
            }
            logger.debug("Stopped ZWave thread: Receive");

            serialPort.removeEventListener();
        }
    }

    @Override
    public void deviceDiscovered(int node) {
        throw new UnsupportedOperationException("deviceDiscovered not implemented");
    }

    @Override
    public void sendPacket(SerialMessage serialMessage) {
        byte[] buffer = serialMessage.getMessageBuffer();
        if (serialPort == null) {
            logger.debug("NODE {}: Port closed sending REQUEST Message = {}", serialMessage.getMessageNode(),
                    SerialMessage.bb2hex(buffer));
            return;
        }

        logger.debug("NODE {}: Sending REQUEST Message = {}", serialMessage.getMessageNode(),
                SerialMessage.bb2hex(buffer));

        try {
            synchronized (serialPort.getOutputStream()) {
                serialPort.getOutputStream().write(buffer);
                serialPort.getOutputStream().flush();
                logger.debug("Message SENT");
            }
        } catch (IOException e) {
            logger.error("Got I/O exception {} during sending. exiting thread.", e.getLocalizedMessage());
        }
    }

}
