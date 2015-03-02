package tud.seemuh.nfcgate.network;

import android.nfc.Tag;
import android.util.Log;

import tud.seemuh.nfcgate.hce.DaemonConfiguration;
import tud.seemuh.nfcgate.network.meta.MetaMessage;
import tud.seemuh.nfcgate.reader.IsoDepReaderImpl;
import tud.seemuh.nfcgate.reader.NFCTagReader;
import tud.seemuh.nfcgate.reader.NfcAReaderImpl;
import tud.seemuh.nfcgate.util.Utils;
import tud.seemuh.nfcgate.network.c2c.C2C;
import tud.seemuh.nfcgate.network.c2s.C2S;
import tud.seemuh.nfcgate.network.meta.MetaMessage.Wrapper.MessageCase;
import tud.seemuh.nfcgate.network.c2c.C2C.Status.StatusCode;
import tud.seemuh.nfcgate.network.c2s.C2S.Session.SessionOpcode;
import tud.seemuh.nfcgate.hce.ApduService;


public class CallbackImpl implements Callback {
    private final static String TAG = "CallbackImpl";

    private ApduService apdu;
    private NFCTagReader mReader = null;
    private HighLevelNetworkHandler Handler = NetHandler.getInstance();

    private String SessionToken;

    public Callback setAPDUService(ApduService as) {
        apdu = as;
        return this;
    }

    public CallbackImpl() {}

    @Override
    public void notifyBrokenPipe() {
        Handler.disconnectBrokenPipe();
    }

    /**
     * Implementation of SimpleNetworkConnectionClientImpl.Callback
     * @param data: received bytes
     */
    @Override
    public void onDataReceived(byte[] data) {
        try {
            // Parse incoming data as a MetaMessage
            MetaMessage.Wrapper Wrapper = MetaMessage.Wrapper.parseFrom(data);

            handleWrapperMessage(Wrapper);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            // We have received a message in an invalid format.
            // Send error message
            Log.e(TAG, "onDataReceived: Message was malformed, discarding and sending error message");
            Handler.notifyInvalidMsgFormat();
        }
    }

    private void handleWrapperMessage(MetaMessage.Wrapper Wrapper) {
        // Determine which type of Message the MetaMessage contains
        if (Wrapper.getMessageCase() == MessageCase.DATA) {
            Log.i(TAG, "onDataReceived: MessageCase.DATA: Sending to handler");
            handleData(Wrapper.getData());
        }
        else if (Wrapper.getMessageCase() == MessageCase.NFCDATA) {
            Log.i(TAG, "onDataReceived: MessageCase:NFCDATA: Sending to handler");
            handleNFCData(Wrapper.getNFCData());
        }
        else if (Wrapper.getMessageCase() == MessageCase.SESSION) {
            Log.i(TAG, "onDataReceived: MessageCase.SESSION: Sending to handler");
            handleSession(Wrapper.getSession());
        }
        else if (Wrapper.getMessageCase() == MessageCase.STATUS) {
            Log.i(TAG, "onDataReceived: MessageCase.STATUS: Sending to handler");
            handleStatus(Wrapper.getStatus());
        }
        else if (Wrapper.getMessageCase() == MessageCase.ANTICOL) {
            Log.i(TAG, "onDataReceived: MessageCase.ANTICOL: Sending to handler");
            handleAnticol(Wrapper.getAnticol());
        }
        else {
            Log.e(TAG, "onDataReceived: Message fits no known case! This is fucked up");
            Handler.notifyUnknownMessageType();
        }
    }


    private void handleData(C2S.Data msg) {
        if (msg.hasBlob()) {
            try {
                // Decode binary blob into Wrapper message
                MetaMessage.Wrapper Wrapper = MetaMessage.Wrapper.parseFrom(msg.getBlob().toByteArray());

                // Now handle the wrapper Message
                handleWrapperMessage(Wrapper);
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                // We have received a message in an invalid format.
                // Send error message
                Log.e(TAG, "handleData: Message was malformed, discarding and sending error message");
                Handler.notifyInvalidMsgFormat();
            }
        } else if (msg.hasErrcode()) {
            if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_NO_SESSION) {
                Log.e(TAG, "Appearently, we sent a message without being in a session");
            } else if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_TRANSMISSION_FAILED) {
                Log.e(TAG, "Appearently, our partner dropped (Transmission failed)");
                // TODO Implement
            } else if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_UNKNOWN) {
                Log.e(TAG, "An unknown error occured. Interesting.");
            } else if (msg.getErrcode() == C2S.Data.DataErrorCode.ERROR_NOERROR) {
                Log.d(TAG, "Message was forwarded successfully by the server. Doing nothing.");
            } else {
                Log.e(TAG, "Data message with unknown error code detected.");
                Handler.notifyInvalidMsgFormat();
            }
        } else {
            Log.e(TAG, "Got message without blob and errorcode. What.");
            Handler.notifyInvalidMsgFormat();
        }
    }

    private void handleAnticol(C2C.Anticol msg) {
        Log.i(TAG, "handleAnticol: got anticol values");

        byte[] a_atqa = msg.getATQA().toByteArray();
        byte atqa = a_atqa.length > 0 ? a_atqa[a_atqa.length-1] : 0;

        byte[] a_hist = msg.getHistoricalByte().toByteArray();
        byte hist = a_hist.length > 0 ? a_atqa[0] : 0;

        byte[] a_sak = msg.getSAK().toByteArray();
        byte sak = a_sak.length > 0 ? a_sak[0] : 0;

        DaemonConfiguration.getInstance().uploadConfiguration(atqa, sak, hist, msg.getUID().toByteArray());
        DaemonConfiguration.getInstance().enablePatch();
    }


    private void handleNFCData(C2C.NFCData msg) {
        if (msg.getDataSource() == C2C.NFCData.DataSource.READER) {
            // We received a signal FROM a reader device and are required to talk TO a card.
            if (mReader.isConnected()) {
                Log.i(TAG, "HandleNFCData: Received message for a card, forwarding...");
                Log.d(TAG, "HandleNFCData: " + Utils.bytesToHex(msg.getDataBytes().toByteArray()));
                // Extract NFC Bytes and send them to the card
                byte[] bytesFromCard = mReader.sendCmd(msg.getDataBytes().toByteArray());

                // Send the reply from the card to the partner
                Handler.sendAPDUReply(bytesFromCard);

                Log.i(TAG, "HandleNFCData: Received and forwarded reply from card");
                Log.i(TAG, "HandleNFCData: BytesFromCard: " + Utils.bytesToHex(bytesFromCard));
            } else {
                Log.e(TAG, "HandleNFCData: No NFC connection active");
                // There is no connected NFC device
                Handler.notifyNFCNotConnected();
            }
        } else {
            if (apdu != null) {
                Log.i(TAG, "HandleNFCData: Received a message for a reader, forwarding...");
                // We received a signal FROM a card and are required to talk TO a reader.
                apdu.sendResponse(msg.getDataBytes().toByteArray());
            } else {
                Log.e(TAG, "HandleNFCData: Received a message for a reader, but no APDU instance active.");
                Handler.notifyNFCNotConnected();
            }
        }
    }


    private void handleStatus(C2C.Status msg) {
        if (msg.getCode() == StatusCode.KEEPALIVE_REQ) {
            // Received keepalive request, reply with response
            Log.i(TAG, "handleStatus: Got Keepalive request, replying");
            Handler.sendKeepaliveReply();
        }
        else if (msg.getCode() == StatusCode.KEEPALIVE_REP) {
            // Got keepalive response, do nothing for now
            Log.i(TAG, "handleStatus: Got Keepalive response. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.NOT_IMPLEMENTED) {
            Log.e(TAG, "handleStatus: Other party sent NOT_IMPLEMENTED. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.UNKNOWN_ERROR) {
            Log.e(TAG, "handleStatus: Other party sent UNKNOWN_ERROR. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.UNKNOWN_MESSAGE) {
            Log.e(TAG, "handleStatus: Other party sent UNKNOWN_MESSAGE. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.INVALID_MSG_FMT) {
            Log.e(TAG, "handleStatus: Other party sent INVALID_MSG_FMT. Doing nothing");
        }
        else if (msg.getCode() == StatusCode.READER_FOUND) {
            Log.d(TAG, "handleStatus: Other party sent READER_FOUND. Delegating to NetHandler.");
            Handler.sessionPartnerAPDUModeOn();
        }
        else if (msg.getCode() == StatusCode.READER_REMOVED) {
            Log.d(TAG, "handleStatus: Other party sent READER_REMOVED. Delegating to NetHandler.");
            Handler.sessionPartnerAPDUModeOff();
        }
        else if (msg.getCode() == StatusCode.CARD_FOUND) {
            Log.d(TAG, "handleStatus: Other party sent CARD_FOUND. Delegating to NetHandler.");
            Handler.sessionPartnerReaderModeOn();
        }
        else if (msg.getCode() == StatusCode.CARD_REMOVED) {
            Log.d(TAG, "handleStatus: Other party sent CARD_REMOVED. Delegating to NetHandler.");
            Handler.sessionPartnerReaderModeOff();
        }
        else if (msg.getCode() == StatusCode.NFC_NO_CONN) {
            Log.d(TAG, "handleStatus: Other party sent NFC_NO_CONN. Delegating to NetHandler.");
            Handler.sessionPartnerNFCLost();
        }
        else {
            // Not implemented
            Log.e(TAG, "handleStatus: Message case not implemented");
            Handler.notifyNotImplemented();
        }
    }


    private void handleSession(C2S.Session msg) {
        if (msg.getOpcode() == SessionOpcode.SESSION_CREATE_FAIL) {
            Log.d(TAG, "handleSession: SESSION_CREATE_FAIL: Delegating to Handler");
            Handler.sessionCreateFailed(msg.getErrcode());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_CREATE_SUCCESS) {
            Log.d(TAG, "handleSession: SESSION_CREATE_SUCCESS: Delegating to Handler");
            // Notify handler about session secret
            Handler.confirmSessionCreation(msg.getSessionSecret());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_JOIN_FAIL) {
            Log.d(TAG, "handleSession: SESSION_JOIN_FAIL: Delegating to Handler");
            Handler.sessionJoinFailed(msg.getErrcode());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_JOIN_SUCCESS) {
            Log.d(TAG, "handleSession: SESSION_JOIN_SUCCESS: Delegating to Handler");
            Handler.confirmSessionJoin();
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_LEAVE_FAIL) {
            Log.d(TAG, "handleSession: SESSION_LEAVE_FAIL: Delegating to Handler");
            Handler.sessionLeaveFailed(msg.getErrcode());
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_LEAVE_SUCCESS) {
            Log.d(TAG, "handleSession: SESSION_LEAVE_SUCCESS: Delegating to Handler");
            Handler.confirmSessionLeave();
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_PEER_JOINED) {
            Log.d(TAG, "handleSession: SESSION_PEER_JOINED: Delegating to Handler");
            Handler.sessionPartnerJoined();
        }
        else if (msg.getOpcode() == SessionOpcode.SESSION_PEER_LEFT) {
            Log.d(TAG, "handleSession: SESSION_PEER_LEFT: Delegating to Handler");
            Handler.sessionPartnerLeft();
        }
        else {
            Log.e(TAG, "handleSession: Unknown Opcode!");
            Handler.notifyInvalidMsgFormat();
        }
    }


    /**
     * Called on nfc tag intent
     * @param tag nfc tag
     * @return true if a supported tag is found
     */
    public boolean setTag(Tag tag) {

        boolean found_supported_tag = false;

        //identify tag type
        for(String type: tag.getTechList()) {
            Log.i(TAG, "setTag: Tag TechList: " + type);
            if("android.nfc.tech.IsoDep".equals(type)) {
                found_supported_tag = true;

                mReader = new IsoDepReaderImpl(tag);
                Log.d(TAG, "setTag: Chose IsoDep technology.");
                break;
            } else if("android.nfc.tech.NfcA".equals(type)) {
                found_supported_tag = true;

                mReader = new NfcAReaderImpl(tag);
                Log.d(TAG, "setTag: Chose NfcA technology.");
                break;
            }
        }

        //set callback when data is received
        if(found_supported_tag){
            SimpleLowLevelNetworkConnectionClientImpl.getInstance().setCallback(this);
            Log.i("NFCGATE_TAG", "UID:  " + Utils.bytesToHex(mReader.getUID()));
            Log.i("NFCGATE_TAG", "ATQA: " + Utils.bytesToHex(mReader.getAtqa()));
            Log.i("NFCGATE_TAG", "SAK:  " + Utils.bytesToHex(mReader.getSak()));
            Log.i("NFCGATE_TAG", "HIST: " + Utils.bytesToHex(mReader.getHistoricalBytes()));
            Handler.sendAnticol(mReader.getAtqa(), mReader.getSak(), mReader.getHistoricalBytes(), mReader.getUID());
        }

        return found_supported_tag;
    }
}
