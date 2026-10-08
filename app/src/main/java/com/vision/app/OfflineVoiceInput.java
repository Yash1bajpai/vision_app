package com.vision.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** Never falls back to a network recognizer. Android 12+ local recognition only. */
public class OfflineVoiceInput {
    public interface Callback { void result(String text); void error(); }
    private SpeechRecognizer recognizer;
    public boolean available(Context c) {
        return Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c);
    }
    public void start(Context c,Callback callback) {
        if (!available(c)) throw new UnsupportedOperationException("No local speech service");
        cancel();
        recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { }
            public void onBeginningOfSpeech() { }
            public void onRmsChanged(float r) { }
            public void onBufferReceived(byte[] b) { }
            public void onEndOfSpeech() { }
            public void onError(int e) { callback.error(); }
            public void onResults(Bundle b) {
                ArrayList<String> list=b==null?null:b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                callback.result(list==null || list.isEmpty()?null:list.get(0));
            }
            public void onPartialResults(Bundle b) { }
            public void onEvent(int type,Bundle b) { }
        });
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        recognizer.startListening(intent);
    }
    public void stop() { if (recognizer!=null) recognizer.stopListening(); }
    public void cancel() {
        if (recognizer!=null) { recognizer.cancel();recognizer.destroy();recognizer=null; }
    }
}
