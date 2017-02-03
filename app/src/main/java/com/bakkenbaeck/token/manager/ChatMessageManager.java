package com.bakkenbaeck.token.manager;


import com.bakkenbaeck.token.BuildConfig;
import com.bakkenbaeck.token.R;
import com.bakkenbaeck.token.crypto.HDWallet;
import com.bakkenbaeck.token.crypto.signal.SignalPreferences;
import com.bakkenbaeck.token.crypto.signal.SignalService;
import com.bakkenbaeck.token.crypto.signal.store.ProtocolStore;
import com.bakkenbaeck.token.crypto.signal.store.SignalTrustStore;
import com.bakkenbaeck.token.manager.model.ChatMessageTask;
import com.bakkenbaeck.token.model.local.ChatMessage;
import com.bakkenbaeck.token.model.local.SendState;
import com.bakkenbaeck.token.model.sofa.Payment;
import com.bakkenbaeck.token.model.sofa.PaymentRequest;
import com.bakkenbaeck.token.model.sofa.SofaAdapters;
import com.bakkenbaeck.token.model.sofa.SofaType;
import com.bakkenbaeck.token.presenter.store.ChatMessageStore;
import com.bakkenbaeck.token.util.LogUtil;
import com.bakkenbaeck.token.util.OnNextSubscriber;
import com.bakkenbaeck.token.view.BaseApplication;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import rx.SingleSubscriber;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

public final class ChatMessageManager {
    private final PublishSubject<ChatMessageTask> chatMessageQueue = PublishSubject.create();

    private SignalService signalService;
    private HDWallet wallet;
    private SignalTrustStore trustStore;
    private ProtocolStore protocolStore;
    private SignalServiceMessagePipe messagePipe;
    private ChatMessageStore chatMessageStore;
    private ExecutorService dbThreadExecutor;
    private String userAgent;
    private SofaAdapters adapters;
    private boolean receiveMessages;

    public final ChatMessageManager init(final HDWallet wallet) {
        this.wallet = wallet;
        this.userAgent = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;
        this.adapters = new SofaAdapters();
        new Thread(new Runnable() {
            @Override
            public void run() {
                initEverything();
            }
        }).start();

        return this;
    }

    // Will send the message to a remote peer
    // and store the message in the local database
    public final void sendAndSaveMessage(final ChatMessage message) {
        final ChatMessageTask messageTask = new ChatMessageTask(message, ChatMessageTask.SEND_AND_SAVE);
        this.chatMessageQueue.onNext(messageTask);
    }

    // Will send the message to a remote peer
    // but not store the message in the local database
    public final void sendMessage(final ChatMessage command) {
        final ChatMessageTask messageTask = new ChatMessageTask(command, ChatMessageTask.SEND_ONLY);
        this.chatMessageQueue.onNext(messageTask);
    }

    // Will store the message in the local database
    // but not send the message to a remote peer
    public final void saveMessage(final ChatMessage message) {
        final ChatMessageTask messageTask = new ChatMessageTask(message, ChatMessageTask.SAVE_ONLY);
        this.chatMessageQueue.onNext(messageTask);
    }

    public final void resumeMessageReceiving() {
        if (haveRegisteredWithServer() && this.wallet != null) {
            receiveMessagesAsync();
        }
    }

    public final void disconnect() {
        this.receiveMessages = false;
        if (this.messagePipe != null) {
            this.messagePipe.shutdown();
            this.messagePipe = null;
        }
    }

    private void initEverything() {
        generateStores();
        initDatabase();
        registerIfNeeded();
        attachSubscribers();
    }

    private void initDatabase() {
        this.dbThreadExecutor = Executors.newSingleThreadExecutor();
        this.dbThreadExecutor.submit(new Runnable() {
            @Override
            public void run() {
                ChatMessageManager.this.chatMessageStore = new ChatMessageStore();
            }
        });
    }

    private void generateStores() {
        this.protocolStore = new ProtocolStore().init();
        this.trustStore = new SignalTrustStore();
        this.signalService = new SignalService(this.trustStore, this.wallet, this.protocolStore, this.userAgent);
    }

    private void registerIfNeeded() {
        if (!haveRegisteredWithServer()) {
            registerWithServer();
        } else {
            receiveMessagesAsync();
        }
    }

    private void registerWithServer() {
        this.signalService.registerKeys(
                this.protocolStore,
                new SingleSubscriber<Void>() {
                    @Override
                    public void onSuccess(final Void aVoid) {
                        SignalPreferences.setRegisteredWithServer();
                        receiveMessagesAsync();
                    }

                    @Override
                    public void onError(final Throwable throwable) {
                        LogUtil.e(getClass(), "Error during key registration: " + throwable);
                    }
                });
    }

    private boolean haveRegisteredWithServer() {
        return SignalPreferences.getRegisteredWithServer();
    }

    private void attachSubscribers() {
        this.chatMessageQueue
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(new OnNextSubscriber<ChatMessageTask>() {
            @Override
            public void onNext(final ChatMessageTask messageTask) {
                dbThreadExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (messageTask.getAction() == ChatMessageTask.SEND_AND_SAVE) {
                            sendMessageToRemotePeer(messageTask.getChatMessage(), true);
                        } else if (messageTask.getAction() == ChatMessageTask.SAVE_ONLY) {
                            storeMessage(messageTask.getChatMessage());
                        } else {
                            sendMessageToRemotePeer(messageTask.getChatMessage(), false);
                        }
                    }
                });
            }
        });
    }

    private void sendMessageToRemotePeer(final ChatMessage message, final boolean saveMessageToDatabase) {
        final SignalServiceMessageSender messageSender = new SignalServiceMessageSender(
                BaseApplication.get().getResources().getString(R.string.chat_url),
                this.trustStore,
                this.wallet.getOwnerAddress(),
                this.protocolStore.getPassword(),
                this.protocolStore,
                this.userAgent,
                null
        );

        if (saveMessageToDatabase) {
            this.chatMessageStore.save(message);
        }

        try {
            messageSender.sendMessage(
                    new SignalServiceAddress(message.getConversationId()),
                    SignalServiceDataMessage.newBuilder()
                            .withBody(message.getAsSofaMessage())
                            .build());

            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_SENT);
                this.chatMessageStore.update(message);
            }
        } catch (final UntrustedIdentityException | IOException ex) {
            LogUtil.error(getClass(), ex.toString());
            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_FAILED);
                this.chatMessageStore.update(message);
            }
        }
    }

    private void storeMessage(final ChatMessage message) {
        message.setSendState(SendState.STATE_LOCAL_ONLY);
        this.chatMessageStore.save(message);
    }

    private void receiveMessagesAsync() {
        this.receiveMessages = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (receiveMessages) {
                    receiveMessages();
                }
            }
        }).start();
    }

    private void receiveMessages() {
        final SignalServiceMessageReceiver messageReceiver = new SignalServiceMessageReceiver(
                BaseApplication.get().getResources().getString(R.string.chat_url),
                this.trustStore,
                this.wallet.getOwnerAddress(),
                this.protocolStore.getPassword(),
                this.protocolStore.getSignalingKey(),
                this.userAgent);
        final SignalServiceAddress localAddress = new SignalServiceAddress(this.wallet.getOwnerAddress());
        final SignalServiceCipher cipher = new SignalServiceCipher(localAddress, this.protocolStore);


        if (this.messagePipe == null) {
            this.messagePipe = messageReceiver.createMessagePipe();
        }

        try {
            final SignalServiceEnvelope envelope = messagePipe.read(10, TimeUnit.SECONDS);
            final SignalServiceContent message = cipher.decrypt(envelope);
            final SignalServiceDataMessage dataMessage = message.getDataMessage().get();
            if (dataMessage != null) {
                final String messageSource = envelope.getSource();
                final String messageBody = dataMessage.getBody().get();
                saveIncomingMessageToDatabase(messageSource, messageBody);
                BaseApplication.get().getTokenManager().getUserManager().tryAddContact(messageSource);
            }
        } catch (final TimeoutException ex) {
            // Nop. This is to be expected
        } catch (final IllegalStateException | InvalidKeyException | InvalidKeyIdException | DuplicateMessageException | InvalidVersionException | LegacyMessageException | InvalidMessageException | NoSessionException | org.whispersystems.libsignal.UntrustedIdentityException | IOException e) {
            LogUtil.e(getClass(), "receiveMessage: " + e.toString());
        }
    }

    private void saveIncomingMessageToDatabase(final String messageSource, final String messageBody) {
        this.dbThreadExecutor.submit(new Runnable() {
            @Override
            public void run() {
                final ChatMessage remoteMessage = new ChatMessage().makeNew(messageSource, false, messageBody);
                if (remoteMessage.getType() == SofaType.PAYMENT_REQUEST) {
                    embedLocalAmountIntoPaymentRequest(remoteMessage);
                } else if(remoteMessage.getType() == SofaType.PAYMENT) {
                    embedLocalAmountIntoPayment(remoteMessage);
                }
                ChatMessageManager.this.chatMessageStore.save(remoteMessage);
            }

            private void embedLocalAmountIntoPayment(final ChatMessage remoteMessage) {
                try {
                    final Payment payment = adapters.paymentFrom(remoteMessage.getPayload());
                    payment.generateLocalPrice();
                    remoteMessage.setPayload(adapters.toJson(payment));
                } catch (IOException e) {
                    // No-op
                }
            }

            private void embedLocalAmountIntoPaymentRequest(final ChatMessage remoteMessage) {
                try {
                    final PaymentRequest request = adapters.txRequestFrom(remoteMessage.getPayload());
                    request.generateLocalPrice();
                    remoteMessage.setPayload(adapters.toJson(request));
                } catch (IOException e) {
                    // No-op
                }
            }
        });
    }
}