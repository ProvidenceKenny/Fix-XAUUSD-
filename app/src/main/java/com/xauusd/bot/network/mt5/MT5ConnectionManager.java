package com.xauusd.bot.network.mt5;

import android.util.Log;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONException;
import org.json.JSONObject;

public class MT5ConnectionManager {
    private static final String TAG = "MT5ConnectionManager";
    private static final long RECONNECT_DELAY_MS = 5000; // 5 seconds
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private String mt5ServerUrl;
    private MT5ConnectionListener listener;
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private int reconnectAttempts = 0;
    private Thread reconnectThread;
    private volatile boolean shouldReconnect = true;

    public interface MT5ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onOrderResponse(JSONObject response);
    }

    public MT5ConnectionManager(String mt5ServerUrl) {
        this.mt5ServerUrl = mt5ServerUrl;
        this.httpClient = new OkHttpClient();
    }

    public void setConnectionListener(MT5ConnectionListener listener) {
        this.listener = listener;
    }

    public void connect() {
        if (isConnected.get()) {
            Log.d(TAG, "Already connected to MT5");
            return;
        }

        shouldReconnect = true;
        reconnectAttempts = 0;
        performConnect();
    }

    private void performConnect() {
        try {
            Log.d(TAG, "Attempting to connect to MT5 at: " + mt5ServerUrl);
            
            URI serverUri = new URI(mt5ServerUrl);
            Request request = new Request.Builder()
                    .url(mt5ServerUrl)
                    .build();

            webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                    Log.d(TAG, "MT5 WebSocket Connected");
                    isConnected.set(true);
                    reconnectAttempts = 0;
                    if (listener != null) {
                        listener.onConnected();
                    }
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    Log.d(TAG, "MT5 Message: " + text);
                    try {
                        JSONObject response = new JSONObject(text);
                        if (listener != null) {
                            listener.onOrderResponse(response);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing MT5 response", e);
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
                    Log.e(TAG, "MT5 Connection Failed", t);
                    isConnected.set(false);
                    if (listener != null) {
                        listener.onError(t.getMessage());
                    }
                    attemptReconnect();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    Log.d(TAG, "MT5 WebSocket Closed: " + reason);
                    isConnected.set(false);
                    if (listener != null) {
                        listener.onDisconnected();
                    }
                    if (shouldReconnect) {
                        attemptReconnect();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to MT5", e);
            if (listener != null) {
                listener.onError(e.getMessage());
            }
            attemptReconnect();
        }
    }

    private void attemptReconnect() {
        if (!shouldReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached or reconnect disabled");
            return;
        }

        reconnectAttempts++;
        long delayMs = RECONNECT_DELAY_MS * reconnectAttempts;
        Log.d(TAG, "Scheduling reconnect in " + delayMs + "ms (attempt " + reconnectAttempts + ")");

        if (reconnectThread != null && reconnectThread.isAlive()) {
            return;
        }

        reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                if (shouldReconnect && !isConnected.get()) {
                    performConnect();
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Reconnect thread interrupted", e);
            }
        });
        reconnectThread.start();
    }

    public void sendOrder(JSONObject orderData) {
        if (!isConnected.get()) {
            Log.e(TAG, "Not connected to MT5, cannot send order");
            if (listener != null) {
                listener.onError("MT5 not connected");
            }
            return;
        }

        try {
            String orderJson = orderData.toString();
            Log.d(TAG, "Sending order to MT5: " + orderJson);
            webSocket.send(orderJson);
        } catch (Exception e) {
            Log.e(TAG, "Error sending order to MT5", e);
            if (listener != null) {
                listener.onError("Failed to send order: " + e.getMessage());
            }
        }
    }

    public void disconnect() {
        shouldReconnect = false;
        if (webSocket != null) {
            webSocket.close(1000, "Client closing connection");
        }
        isConnected.set(false);
    }

    public boolean isConnected() {
        return isConnected.get();
    }
}
