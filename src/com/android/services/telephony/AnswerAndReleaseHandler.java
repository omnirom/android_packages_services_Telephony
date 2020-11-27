/*
 * Copyright (c) 2020, The Linux Foundation. All rights reserved.

 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.

 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.services.telephony;

import android.os.Bundle;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telecom.StatusHints;
import android.telecom.VideoProfile;

import com.android.ims.internal.ConferenceParticipant;

import java.util.Collection;
import java.util.List;

import java.util.concurrent.CopyOnWriteArrayList;

public class AnswerAndReleaseHandler extends TelephonyConnection.TelephonyConnectionListener {

    private List<Connection> mConnectionList = new CopyOnWriteArrayList<>();
    private List<Conference> mConferenceList = new CopyOnWriteArrayList<>();
    private int mVideoState;
    private Connection mIncomingConnection = null;
    private List<Listener> mListeners = new CopyOnWriteArrayList<>();

    public AnswerAndReleaseHandler(Connection incomingConnection, int answerWithVideoState) {
        mVideoState = answerWithVideoState;
        mIncomingConnection = incomingConnection;
    }

    public interface Listener {
        void onAnswered();
    }

    public static class ListenerBase implements Listener {
        @Override
        public void onAnswered() {}
    }

    public void addListener(Listener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    private void notifyOnAnswered() {
        synchronized (mListeners) {
            for (Listener l : mListeners) {
                l.onAnswered();
            }
        }
    }

    private final TelephonyConferenceBase.TelephonyConferenceListener mTelephonyConferenceListener =
            new TelephonyConferenceBase.TelephonyConferenceListener() {
        @Override
        public void onDestroyed(Conference conference) {
            removeConference(conference);
            maybeAnswer();
        }
    };

    public void checkAndAnswer(Collection<Connection> allConnections,
            Collection<Conference> allConferences) {
        for (Connection current : allConnections) {
            // Connection list could contain other types like conference
            // participant connections which need to be ignored
            if (!(current instanceof TelephonyConnection)) {
                continue;
            }
            int state = current.getState();
            if (state == Connection.STATE_RINGING ||
                    state == Connection.STATE_DISCONNECTED) {
                continue;
            }
            boolean containsConnection = false;
            synchronized(mConnectionList) {
                containsConnection = mConnectionList.contains(current);
            }
            if (!containsConnection) {
                addConnection(current);
                TelephonyConnection conn = (TelephonyConnection) current;
                conn.addTelephonyConnectionListener(this);
                conn.onDisconnect();
            }
        }
        for (Conference current : allConferences) {
            if (!(current instanceof TelephonyConferenceBase)) {
                continue;
            }
            if (current.getState() == Connection.STATE_DISCONNECTED) {
                continue;
            }
            boolean containsConference = false;
            synchronized(mConferenceList) {
                containsConference = mConferenceList.contains(current);
            }
            if (!containsConference) {
                addConference(current);
                TelephonyConferenceBase conf = (TelephonyConferenceBase) current;
                conf.addTelephonyConferenceListener(mTelephonyConferenceListener);
                current.onDisconnect();
            }
        }
        maybeAnswer();
    }

    private void maybeAnswer() {
        boolean isConnectionListEmpty = false;
        synchronized(mConnectionList) {
            isConnectionListEmpty = mConnectionList.isEmpty();
        }
        boolean isConferenceListEmpty = false;
        synchronized(mConferenceList) {
            isConferenceListEmpty = mConferenceList.isEmpty();
        }
        if (isConnectionListEmpty && isConferenceListEmpty) {
            if (mIncomingConnection.getState() == Connection.STATE_RINGING) {
                mIncomingConnection.onAnswer(mVideoState);
            }
            notifyOnAnswered();
        }
    }

    private void addConnection(Connection conn) {
        synchronized(mConnectionList) {
            mConnectionList.add(conn);
        }
    }

    private void removeConnection(Connection conn) {
        synchronized(mConnectionList) {
            mConnectionList.remove(conn);
        }
    }

    private void addConference(Conference conf) {
        synchronized(mConferenceList) {
            mConferenceList.add(conf);
        }
    }

    private void removeConference(Conference conf) {
        synchronized(mConferenceList) {
            mConferenceList.remove(conf);
        }
    }

    @Override
    public void onOriginalConnectionConfigured(TelephonyConnection c) {}

    @Override
    public void onOriginalConnectionRetry(TelephonyConnection c, boolean isPermanentFailure) {}

    @Override
    public void onConferenceParticipantsChanged(Connection c,
            List<ConferenceParticipant> participants) {}

    @Override
    public void onConferenceStarted() {}

    @Override
    public void onConferenceSupportedChanged(Connection c, boolean isConferenceSupported) {}

    @Override
    public void onConnectionCapabilitiesChanged(Connection c, int connectionCapabilities) {}

    @Override
    public void onConnectionEvent(Connection c, String event, Bundle extras) {}

    @Override
    public void onConnectionPropertiesChanged(Connection c, int connectionProperties) {}

    @Override
    public void onExtrasChanged(Connection c, Bundle extras) {}

    @Override
    public void onExtrasRemoved(Connection c, List<String> keys) {}

    @Override
    public void onStateChanged(android.telecom.Connection c, int state) {}

    @Override
    public void onStatusHintsChanged(Connection c, StatusHints statusHints) {}

    @Override
    public void onDestroyed(Connection c) {}

    @Override
    public void onDisconnected(android.telecom.Connection c,
            android.telecom.DisconnectCause disconnectCause) {
        removeConnection(c);
        maybeAnswer();
    }

    @Override
    public void onVideoProviderChanged(android.telecom.Connection c,
            Connection.VideoProvider videoProvider) {}

    @Override
    public void onVideoStateChanged(android.telecom.Connection c, int videoState) {}

    @Override
    public void onRingbackRequested(Connection c, boolean ringback) {}
}
