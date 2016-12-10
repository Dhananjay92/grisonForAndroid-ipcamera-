package com.sibilantsolutions.grisonforandroid;

import android.app.ListActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
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
import com.sibilantsolutions.grisonforandroid.domain.CamDef;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

public class MainActivity extends ListActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQ_ADD_CAM = 1;
    public static final String KEY_CAM_DEFS = "KEY_CAM_DEFS";
//    private AudioTrack audioTrack;

    private SharedPreferences sharedPreferences;

    private MyCamArrayAdapter myCamArrayAdapter;
    private ActionMode mActionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        final List<CamSession> camSessions = new ArrayList<>();

        final Set<String> strings = sharedPreferences.getStringSet(KEY_CAM_DEFS, null);
        if (strings != null) {
            for (String str : strings) {
                final CamDef camDef = deserialize(str);
                CamSession camSession = new CamSession();
                camSession.camDef = camDef;
                camSession.camStatus = CamStatus.CONNECTING;
                camSessions.add(camSession);
            }
        }

        myCamArrayAdapter = new MyCamArrayAdapter(this, camSessions);

        setListAdapter(myCamArrayAdapter);

        getListView().setOnItemLongClickListener(new OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (mActionMode != null) {
                    return false;
                }

                // Start the CAB
                mActionMode = startActionMode(actionModeCallback(position));
                view.setSelected(true);
                return true;
            }
        });
    }

    private Callback actionModeCallback(final int position) {
        return new Callback() {

            // Called when the action mode is created; startActionMode() was called
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                // Inflate a menu resource providing context menu items
                MenuInflater inflater = mode.getMenuInflater();
                inflater.inflate(R.menu.context_menu, menu);
                return true;
            }

            // Called each time the action mode is shown. Always called after onCreateActionMode, but
            // may be called multiple times if the mode is invalidated.
            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false; // Return false if nothing is done
            }

            // Called when the user selects a contextual menu item
            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.delete_cam:
                        deleteItem(position);
                        mode.finish(); // Action picked, so close the CAB
                        return true;
                    default:
                        return false;
                }
            }

            // Called when the user exits the action mode
            @Override
            public void onDestroyActionMode(ActionMode mode) {
                mActionMode = null;
            }

        };
    }

    private void deleteItem(final int position) {
        final CamSession camSession = myCamArrayAdapter.getItem(position);
        assert camSession != null;
        if (camSession.foscamSession != null) {
            camSession.foscamSession.disconnect();  //TODO: Get off the UI thread.
            camSession.foscamSession = null;
        }
        Set<String> strings = sharedPreferences.getStringSet(KEY_CAM_DEFS, null);
        if (strings != null) {
            //Make a copy because the returned obj is not guaranteed to be editable.
            strings = new HashSet<>(strings);
            for (Iterator<String> iter = strings.iterator(); iter.hasNext(); ) {
                String camDefStr = iter.next();
                CamDef camDef = deserialize(camDefStr);
                if (camSession.camDef.equals(camDef)) {
                    iter.remove();
                    break;
                }
            }
            final Editor editor = sharedPreferences.edit();
            editor.putStringSet(KEY_CAM_DEFS, strings);
            editor.apply();
        } else {
            Log.e(TAG, "deleteItem: cam defs was null");
        }

        myCamArrayAdapter.remove(camSession);
    }


    public void onClickAddCam(View view) {
        startActivityForResult(new Intent(this, AddCamActivity.class), REQ_ADD_CAM);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ADD_CAM && resultCode == RESULT_OK) {
            final CamDef camDef = (CamDef) data.getSerializableExtra(AddCamActivity.EXTRA_CAM_DEF);

            Set<String> strings = sharedPreferences.getStringSet(KEY_CAM_DEFS, null);
            if (strings == null) {
                strings = new HashSet<>();
            } else {
                //Make a copy because the returned obj is not guaranteed to be editable.
                strings = new HashSet<>(strings);
            }
            strings.add(serialize(camDef));
            final Editor editor = sharedPreferences.edit();
            editor.putStringSet(KEY_CAM_DEFS, strings);
            editor.apply();

            CamSession camSession = new CamSession();
            camSession.camDef = camDef;
            camSession.camStatus = CamStatus.CONNECTING;

            myCamArrayAdapter.add(camSession);

            startCam(camSession);
        }
    }

    CamDef deserialize(String s) {
        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(s.getBytes(ISO_8859_1));
        final Object obj;
        try {
            ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
            obj = objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new UnsupportedOperationException("TODO (CSB)");
        }

        if (obj instanceof CamDef) {
            return (CamDef) obj;
        }

        throw new IllegalArgumentException("Expected obj type=" + CamDef.class.getName() + ", got=" + obj.getClass()
                .getName());
    }

    String serialize(CamDef camDef) {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            final ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(camDef);
        } catch (IOException e) {
            throw new UnsupportedOperationException("TODO (CSB)", e);
        }

        final byte[] bytes = byteArrayOutputStream.toByteArray();
        return new String(bytes, ISO_8859_1);
    }

    private enum CamStatus {
        CONNECTING,
        CONNECTED,
        CANT_CONNECT,
        LOST_CONNECTION
    }

    private static class CamSession {
        CamDef camDef;
        CamStatus camStatus;
        String reason = "UNKNOWN";
        Bitmap curBitmap;
        FoscamSession foscamSession;
    }

    private static class MyCamArrayAdapter extends ArrayAdapter<CamSession> {

        private final MainActivity activity;

        MyCamArrayAdapter(MainActivity context, List<CamSession> objects) {
            super(context, R.layout.card_cam_summary, objects);
            this.activity = context;
        }

        private static class ViewHolder {
            ImageView camPreview;
            ProgressBar camLoadingProgressBar;
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
                viewHolder.camLoadingProgressBar = (ProgressBar) convertView.findViewById(R.id.cam_image_progress_bar);
                viewHolder.camName = (TextView) convertView.findViewById(R.id.cam_name);
                viewHolder.camAddress = (TextView) convertView.findViewById(R.id.cam_address);
                viewHolder.camStatus = (TextView) convertView.findViewById(R.id.cam_status);
            }

            final ViewHolder viewHolder = (ViewHolder) convertView.getTag();

            CamSession camSession = getItem(position);
            assert camSession != null;
            CamDef camDef = camSession.camDef;
            viewHolder.camName.setText(camDef.getName());
            viewHolder.camAddress.setText(String.format(Locale.ROOT, "%s@%s:%d", camDef.getUsername(), camDef.getHost
                    (), camDef.getPort()));

            switch (camSession.camStatus) {
                case CANT_CONNECT:
                case LOST_CONNECTION:
                    viewHolder.camLoadingProgressBar.setVisibility(View.INVISIBLE);
                    viewHolder.camPreview.setImageDrawable(getContext().getDrawable(android.R.drawable
                            .ic_dialog_alert));
                    viewHolder.camPreview.setVisibility(View.VISIBLE);
                    viewHolder.camStatus.setText(camSession.reason);
                    break;

                case CONNECTED:
                    viewHolder.camLoadingProgressBar.setVisibility(View.INVISIBLE);
                    viewHolder.camStatus.setText(R.string.connected);
                    if (camSession.curBitmap != null) {
                        viewHolder.camPreview.setImageBitmap(camSession.curBitmap);
                        viewHolder.camStatus.append(String.format(Locale.ROOT, " (%d x %d)", camSession.curBitmap
                                .getWidth(), camSession.curBitmap.getHeight()));
                    } else {
                        viewHolder.camPreview.setImageDrawable(getContext().getDrawable(android.R.drawable
                                .ic_menu_camera));
                    }
                    viewHolder.camPreview.setVisibility(View.VISIBLE);
                    break;

                case CONNECTING:
                    viewHolder.camLoadingProgressBar.setVisibility(View.VISIBLE);
                    viewHolder.camPreview.setVisibility(View.INVISIBLE);
                    viewHolder.camStatus.setText(R.string.connecting);
                    break;

                default:
                    throw new IllegalArgumentException("Unexpected status=" + camSession.camStatus);
            }

            return convertView;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        for (int i = 0; i < myCamArrayAdapter.getCount(); i++) {
            CamSession camSession = myCamArrayAdapter.getItem(i);
            startCam(camSession);
        }

    }

    private void startCam(final CamSession camSession) {
        Runnable r = new Runnable() {

            @Override
            public void run() {
                CamDef camDef = camSession.camDef;
                String host = camDef.getHost();
                int port = camDef.getPort();
                String username = camDef.getUsername();
                String password = camDef.getPassword();

                if (TextUtils.isEmpty(host) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
                    Log.i(TAG, "run: nothing to do.");
                    return;
                }

                final InetSocketAddress address = new InetSocketAddress(host, port);

                //TODO: Need to handle failure to connect the TCP socket.
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
                                camSession.curBitmap = bMap;
                                notifyDataSetChangedOnUiThread();
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

//                int millisecondsToBuffer = 150;
//                double percentOfASecondToBuffer = millisecondsToBuffer / 1000.0;
//                int bitsPerByte = 8;
//                int bufferSizeInBytes = (int) ((AdpcmDecoder.SAMPLE_SIZE_IN_BITS / bitsPerByte) * AdpcmDecoder
// .SAMPLE_RATE * percentOfASecondToBuffer);
//                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, (int) AdpcmDecoder.SAMPLE_RATE,
//                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes, AudioTrack
// .MODE_STREAM);
//
//                AudioHandlerI audioHandler = new AudioHandlerI() {
//                    AdpcmDecoder adpcmDecoder = new AdpcmDecoder();
//
//                    @Override
//                    public void onAudioStopped(AudioStoppedEvt audioStoppedEvt) {
//                        //No-op.
//                    }
//
//                    @Override
//                    public void onReceive(AudioDataText audioData) {
//                        byte[] bytes = adpcmDecoder.decode(audioData.getDataContent());
//                        short[] shorts = byteArrayToShortArray(bytes, AdpcmDecoder.BIG_ENDIAN ? ByteOrder
// .BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
//                        int numWritten = audioTrack.write(shorts, 0, shorts.length);
//                        if (numWritten != shorts.length) {
//                            throw new UnsupportedOperationException("array len=" + shorts.length + " but only wrote
// " + numWritten + "byte(s)");
//                        }
//                    }
//                };
                final AudioHandlerI audioHandler = new AudioHandlerI() {
                    @Override
                    public void onAudioStopped(AudioStoppedEvt audioStoppedEvt) {
                        //No-op.
                    }

                    @Override
                    public void onReceive(AudioDataText audioData) {
                        //No-op.
                    }
                };
                LostConnectionHandlerI lostConnHandler = new LostConnectionHandlerI() {
                    @Override
                    public void onLostConnection(LostConnectionEvt evt) {
                        Log.i(TAG, "onLostConnection: ");
                        camSession.camStatus = CamStatus.LOST_CONNECTION;
                        camSession.reason = "Lost connection";
                        notifyDataSetChangedOnUiThread();
                    }
                };

                FoscamSession foscamSession;
                boolean success = false;
                try {
                    camSession.camStatus = CamStatus.CONNECTING;
                    notifyDataSetChangedOnUiThread();
                    foscamSession = FoscamSession.connect(address, username, password, audioHandler,
                            imageHandler, alarmHandler, lostConnHandler);
                    success = foscamSession.videoStart();
                    camSession.camStatus = success ? CamStatus.CONNECTED : CamStatus.CANT_CONNECT;
                    if (!success) {
                        camSession.reason = "Connected but couldn't start video";
                        foscamSession.disconnect();
                    } else {
                        camSession.foscamSession = foscamSession;
                    }
                    notifyDataSetChangedOnUiThread();
                } catch (Exception e) {
                    Log.i(TAG, "run: " + address, e);
                    camSession.camStatus = success ? CamStatus.CONNECTED : CamStatus.CANT_CONNECT;
                    camSession.reason = "Could not connect to " + address;
                    if (e.getLocalizedMessage() != null) {
                        camSession.reason += ": " + e.getLocalizedMessage();
                    }
                    notifyDataSetChangedOnUiThread();
                }
//                Log.i(TAG, "run: videoStart success=" + success);
//                boolean audioStartSuccess = foscamSession.audioStart();
//                if (audioStartSuccess) {
//                    audioTrack.play();
//                }
                String audioStartSuccess = "N/A";
                Log.i(TAG, "run: videoStart success=" + success + ", audioStartSuccess=" + audioStartSuccess);
            }
        };
        new Thread(r, "mySessionConnector").start();
    }

    private void notifyDataSetChangedOnUiThread() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                myCamArrayAdapter.notifyDataSetChanged();
            }
        });
    }


    @Override
    protected void onStop() {
        super.onStop();

//        if (audioTrack != null) {
//            audioTrack.pause();
//            audioTrack.flush();
//            audioTrack.release();
//            audioTrack = null;
//        }

        for (int i = 0; i < myCamArrayAdapter.getCount(); i++) {
            CamSession camSession = myCamArrayAdapter.getItem(i);
            assert camSession != null;
            if (camSession.foscamSession != null) {
                camSession.foscamSession.disconnect();
                camSession.foscamSession = null;
            }
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

}
