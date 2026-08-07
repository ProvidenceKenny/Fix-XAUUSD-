package com.xauusd.bot.service;

import android.content.Context;
import android.util.Log;
import com.xauusd.bot.network.api.SignalResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BotSignalProcessor {
    private static final String TAG = "BotSignalProcessor";
    
    private Context context;
    private MT5TradeExecutor tradeExecutor;
    private List<SignalProcessorListener> listeners = new ArrayList<>();
    private ScheduledExecutorService executorService;
    private volatile boolean isProcessing = false;

    public interface SignalProcessorListener {
        void onSignalProcessed(SignalResponse signal);
        void onProcessingError(String error);
    }

    public BotSignalProcessor(Context context, MT5TradeExecutor tradeExecutor) {
        this.context = context;
        this.tradeExecutor = tradeExecutor;
        this.executorService = Executors.newScheduledThreadPool(2);
    }

    public void addListener(SignalProcessorListener listener) {
        listeners.add(listener);
    }

    public void startProcessing() {
        if (isProcessing) {
            Log.w(TAG, "Processing already started");
            return;
        }

        isProcessing = true;
        Log.d(TAG, "Starting signal processing...");
    }

    public void stopProcessing() {
        isProcessing = false;
        Log.d(TAG, "Stopping signal processing...");
    }

    public void processSignal(SignalResponse signal) {
        if (!isProcessing) {
            Log.w(TAG, "Processing not started. Skipping signal.");
            return;
        }

        if (signal == null) {
            String error = "Received null signal";
            Log.e(TAG, error);
            notifyError(error);
            return;
        }

        try {
            // Validate signal
            validateSignal(signal);
            
            // Log signal details
            Log.d(TAG, "Processing signal: Type=" + signal.getType() + 
                   ", Symbol=" + signal.getSymbol() + 
                   ", Entry=" + signal.getEntryPrice() + 
                   ", SL=" + signal.getStopLoss() + 
                   ", TP=" + signal.getTakeProfit());

            // Execute trade via MT5
            if (tradeExecutor.isConnected()) {
                executorService.execute(() -> {
                    tradeExecutor.executeTrade(signal);
                    notifySignalProcessed(signal);
                });
            } else {
                String error = "MT5 not connected. Cannot process signal.";
                Log.e(TAG, error);
                notifyError(error);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Signal validation failed: " + e.getMessage());
            notifyError("Signal validation failed: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error processing signal", e);
            notifyError("Error processing signal: " + e.getMessage());
        }
    }

    private void validateSignal(SignalResponse signal) throws IllegalArgumentException {
        if (signal.getType() == null || signal.getType().isEmpty()) {
            throw new IllegalArgumentException("Signal type is missing");
        }
        if (signal.getSymbol() == null || signal.getSymbol().isEmpty()) {
            throw new IllegalArgumentException("Symbol is missing");
        }
        if (signal.getEntryPrice() == null || signal.getEntryPrice() <= 0) {
            throw new IllegalArgumentException("Invalid entry price");
        }
        if (signal.getLotSize() == null || signal.getLotSize() <= 0) {
            throw new IllegalArgumentException("Invalid lot size");
        }
        if (signal.getStopLoss() == null || signal.getStopLoss() <= 0) {
            throw new IllegalArgumentException("Invalid stop loss");
        }
        if (signal.getTakeProfit() == null || signal.getTakeProfit() <= 0) {
            throw new IllegalArgumentException("Invalid take profit");
        }
    }

    private void notifySignalProcessed(SignalResponse signal) {
        for (SignalProcessorListener listener : listeners) {
            try {
                listener.onSignalProcessed(signal);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying listener", e);
            }
        }
    }

    private void notifyError(String error) {
        for (SignalProcessorListener listener : listeners) {
            try {
                listener.onProcessingError(error);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying listener of error", e);
            }
        }
    }

    public void shutdown() {
        stopProcessing();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Log.e(TAG, "Executor service shutdown interrupted", e);
            }
        }
    }
}
