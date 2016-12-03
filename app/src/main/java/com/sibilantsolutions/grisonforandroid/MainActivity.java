package com.sibilantsolutions.grisonforandroid;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.sibilantsolutions.grison.driver.foscam.domain.AudioDataText;
import com.sibilantsolutions.grison.driver.foscam.domain.VideoDataText;
import com.sibilantsolutions.grison.driver.foscam.net.FoscamSession;
import com.sibilantsolutions.grison.evt.AlarmEvt;
import com.sibilantsolutions.grison.evt.AlarmHandlerI;
import com.sibilantsolutions.grison.evt.AudioHandlerI;
import com.sibilantsolutions.grison.evt.AudioStoppedEvt;
import com.sibilantsolutions.grison.evt.ImageHandlerI;
import com.sibilantsolutions.grison.evt.LostConnectionEvt;
import com.sibilantsolutions.grison.evt.LostConnectionHandlerI;
import com.sibilantsolutions.grison.evt.VideoStoppedEvt;
import com.sibilantsolutions.grison.sound.adpcm.AdpcmDecoder;
import com.sibilantsolutions.grisonforandroid.domain.CamDef;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public class MainActivity extends ListActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private ImageView mImageView;
    private FoscamSession foscamSession;
    private AudioTrack audioTrack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        mImageView = (ImageView) findViewById(R.id.image_view);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        String host = preferences.getString("host", null);
        int port = Integer.parseInt(preferences.getString("port", "80"));
        String username = preferences.getString("username", null);
        String password = preferences.getString("password", null);

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Log.i(TAG, "run: nothing to do.");
            return;
        }

        setListAdapter(new MyCamAdapter(this, new CamDef[]{new CamDef("Name TODO", host, port, username, password)}));
    }

    private static class MyCamAdapter extends ArrayAdapter<CamDef> {

        public MyCamAdapter(Context context, CamDef[] objects) {
            super(context, R.layout.card_cam_summary, objects);
        }

        private static class ViewHolder {
            ImageView camPreview;
            TextView camName;
            TextView camAddress;
            TextView camStatus;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.card_cam_summary, parent, false);

                ViewHolder viewHolder = new ViewHolder();
                convertView.setTag(viewHolder);

                viewHolder.camPreview = (ImageView) convertView.findViewById(R.id.cam_image_preview);
                viewHolder.camName = (TextView) convertView.findViewById(R.id.cam_name);
                viewHolder.camAddress = (TextView) convertView.findViewById(R.id.cam_address);
                viewHolder.camStatus = (TextView) convertView.findViewById(R.id.cam_status);
            }

            ViewHolder viewHolder = (ViewHolder) convertView.getTag();
            CamDef camDef = getItem(position);
            assert camDef != null;
            viewHolder.camName.setText(camDef.getName());
            viewHolder.camAddress.setText(String.format(Locale.ROOT, "%s@%s:%d", camDef.getUsername(), camDef.getHost
                    (), camDef.getPort()));

            return convertView;
        }
    }


    @Override
    protected void onStart() {
        super.onStart();

        if (true) {
            return;
        }

        Runnable r = new Runnable() {

            @Override
            public void run() {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

                String host = preferences.getString("host", null);
                int port = Integer.parseInt(preferences.getString("port", "80"));
                String username = preferences.getString("username", null);
                String password = preferences.getString("password", null);

                if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
                    Log.i(TAG, "run: nothing to do.");
                    return;
                }

                final InetSocketAddress address = new InetSocketAddress(host, port);

                //TODO: Grison threads should be daemons so they will die when the UI does.
                //TODO: Need to handle failed authentication (have a onAuthSuccess/onAuthFail handler).
                //TODO: Grison separate threads for video vs audio; but -- how to keep them in sync?

                final ImageHandlerI imageHandler = new ImageHandlerI() {
                    @Override
                    public void onReceive(VideoDataText videoData) {
                        byte[] dataContent = videoData.getDataContent();
                        final Bitmap bMap = BitmapFactory.decodeByteArray(dataContent, 0, dataContent.length);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mImageView.setImageBitmap(bMap);
                            }
                        });
                    }

                    @Override
                    public void onVideoStopped(VideoStoppedEvt videoStoppedEvt) {

                    }
                };

                AlarmHandlerI alarmHandler = new AlarmHandlerI() {
                    @Override
                    public void onAlarm(AlarmEvt evt) {
                        //No-op.
                    }
                };

                int millisecondsToBuffer = 150;
                double percentOfASecondToBuffer = millisecondsToBuffer / 1000.0;
                int bitsPerByte = 8;
                int bufferSizeInBytes = (int) ((AdpcmDecoder.SAMPLE_SIZE_IN_BITS / bitsPerByte) * AdpcmDecoder.SAMPLE_RATE * percentOfASecondToBuffer);
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, (int) AdpcmDecoder.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes, AudioTrack.MODE_STREAM);

                AudioHandlerI audioHandler = new AudioHandlerI() {
                    AdpcmDecoder adpcmDecoder = new AdpcmDecoder();

                    @Override
                    public void onAudioStopped(AudioStoppedEvt audioStoppedEvt) {
                        //No-op.
                    }

                    @Override
                    public void onReceive(AudioDataText audioData) {
                        byte[] bytes = adpcmDecoder.decode(audioData.getDataContent());
                        short[] shorts = byteArrayToShortArray(bytes, AdpcmDecoder.BIG_ENDIAN ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                        int numWritten = audioTrack.write(shorts, 0, shorts.length);
                        if (numWritten != shorts.length) {
                            throw new UnsupportedOperationException("array len=" + shorts.length + " but only wrote " + numWritten + "byte(s)");
                        }
                    }
                };
                LostConnectionHandlerI lostConnHandler = new LostConnectionHandlerI() {
                    @Override
                    public void onLostConnection(LostConnectionEvt evt) {
                        Log.i(TAG, "onLostConnection: ");
                    }
                };

                foscamSession = FoscamSession.connect(address, username, password, audioHandler, imageHandler, alarmHandler, lostConnHandler);
                boolean success = foscamSession.videoStart();
//                Log.i(TAG, "run: videoStart success=" + success);
                boolean audioStartSuccess = foscamSession.audioStart();
                if (audioStartSuccess) {
                    audioTrack.play();
                }
                Log.i(TAG, "run: videoStart success=" + success + ", audioStartSuccess=" + audioStartSuccess);
            }
        };
        new Thread(r, "mySessionConnector").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (foscamSession != null) {
            foscamSession.disconnect();
            foscamSession = null;
        }
        if (audioTrack != null) {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.release();
            audioTrack = null;
        }

        if (mImageView != null) {
            mImageView.setImageDrawable(null);
        }
    }

    private short[] byteArrayToShortArray(byte[] bytes, ByteOrder byteOrder) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.order(byteOrder);
        short[] shorts = new short[bytes.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = bb.getShort();
        }

        return shorts;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.menu_main, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);

        switch (item.getItemId()) {

            case R.id.prefs_menu:
                Intent intent = new Intent(this, AppPreferencesActivity.class);
                startActivity(intent);
                return true;

            default:
                return false;
        }
    }

}
