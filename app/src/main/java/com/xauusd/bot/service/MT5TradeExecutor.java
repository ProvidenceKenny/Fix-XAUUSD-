package com.xauusd.bot.service;

import android.content.Context;
import android.util.Log;
import com.xauusd.bot.network.mt5.MT5ConnectionManager;
import com.xauusd.bot.network.api.SignalResponse;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class MT5TradeExecutor implements MT5ConnectionManager.MT5ConnectionListener {
    private static final String TAG = "MT5TradeExecutor";
    private Context context;
    private MT5ConnectionManager connectionManager;
    private TradeExecutionListener executionListener;
    private AtomicLong lastTradeTime = new AtomicLong(0);
    private static final long TRADE_COOLDOWN_MS = 5000; // 5 seconds minimum between trades

    public interface TradeExecutionListener {
        void onTradeExecuted(String orderId, String status);
        void onTradeError(String error);
        void onConnectionStatusChanged(boolean connected);
    }

    public MT5TradeExecutor(Context context, String mt5ServerUrl) {
        this.context = context;
        this.connectionManager = new MT5ConnectionManager(mt5ServerUrl);
        this.connectionManager.setConnectionListener(this);
    }

    public void setExecutionListener(TradeExecutionListener listener) {
        this.executionListener = listener;
    }

    public void connect() {
        Log.d(TAG, "Connecting to MT5 server...");
        connectionManager.connect();
    }

    public void disconnect() {
        Log.d(TAG, "Disconnecting from MT5 server...");
        connectionManager.disconnect();
    }

    public void executeTrade(SignalResponse signal) {
        if (!connectionManager.isConnected()) {
            String error = "MT5 not connected. Cannot execute trade.";
            Log.e(TAG, error);
            if (executionListener != null) {
                executionListener.onTradeError(error);
            }
            return;
        }

        // Check cooldown period
        long currentTime = System.currentTimeMillis();
        long timeSinceLastTrade = currentTime - lastTradeTime.get();
        if (timeSinceLastTrade < TRADE_COOLDOWN_MS) {
            String warning = "Trade cooldown active. " + (TRADE_COOLDOWN_MS - timeSinceLastTrade) + "ms remaining";
            Log.w(TAG, warning);
            if (executionListener != null) {
                executionListener.onTradeError(warning);
            }
            return;
        }

        try {
            JSONObject orderData = buildOrderJson(signal);
            Log.d(TAG, "Executing trade with order: " + orderData.toString());
            connectionManager.sendOrder(orderData);
            lastTradeTime.set(currentTime);
        } catch (JSONException e) {
            Log.e(TAG, "Error building order JSON", e);
            if (executionListener != null) {
                executionListener.onTradeError("Error building order: " + e.getMessage());
            }
        }
    }

    private JSONObject buildOrderJson(SignalResponse signal) throws JSONException {
        JSONObject order = new JSONObject();
        order.put("action", "ORDER_PLACE");
        order.put("symbol", signal.getSymbol() != null ? signal.getSymbol() : "XAUUSD");
        order.put("type", signal.getType() != null ? signal.getType() : "BUY");
        order.put("volume", signal.getLotSize() != null ? signal.getLotSize() : 0.1);
        order.put("price", signal.getEntryPrice() != null ? signal.getEntryPrice() : 0.0);
        order.put("stopLoss", signal.getStopLoss() != null ? signal.getStopLoss() : 0.0);
        order.put("takeProfit", signal.getTakeProfit() != null ? signal.getTakeProfit() : 0.0);
        order.put("comment", "XAUUSD Bot Trade - " + getCurrentTimestamp());
        order.put("timestamp", System.currentTimeMillis());
        order.put("clientId", android.os.Build.SERIAL);
        
        return order;
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        return sdf.format(new Date());
    }

    @Override
    public void onConnected() {
        Log.d(TAG, "Connected to MT5");
        if (executionListener != null) {
            executionListener.onConnectionStatusChanged(true);
        }
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Disconnected from MT5");
        if (executionListener != null) {
            executionListener.onConnectionStatusChanged(false);
        }
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "MT5 Error: " + error);
        if (executionListener != null) {
            executionListener.onTradeError(error);
        }
    }

    @Override
    public void onOrderResponse(JSONObject response) {
        try {
            String status = response.optString("status", "UNKNOWN");
            String orderId = response.optString("orderId", "N/A");
            
            Log.d(TAG, "Order Response - ID: " + orderId + ", Status: " + status);
            
            if (executionListener != null) {
                executionListener.onTradeExecuted(orderId, status);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing order response", e);
            if (executionListener != null) {
                executionListener.onTradeError("Error processing response: " + e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        return connectionManager.isConnected();
    }
}
