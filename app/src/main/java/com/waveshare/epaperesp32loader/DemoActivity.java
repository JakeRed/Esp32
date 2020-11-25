package com.waveshare.epaperesp32loader;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.waveshare.epaperesp32loader.communication.BluetoothHelper;
import com.waveshare.epaperesp32loader.image_processing.EPaperDisplay;
import com.waveshare.epaperesp32loader.image_processing.EPaperPicture;

import util.QRCodeUtil;

public class DemoActivity extends AppCompatActivity {
    public static final int REQ_BLUETOOTH_CONNECTION = 2;
    public static BluetoothDevice btDevice;
    private TextView text_blue;
    private EditText ed_url;
    @Nullable
    private Bitmap bitmap;
    private SocketHandler handler;
    private TextView textView,tv_prefile;
    private ImageView iv_bitmap;
    private LinearLayout ll_bitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_demo_activity);
        text_blue = findViewById(R.id.text_blue);
        ed_url = findViewById(R.id.ed_url);
        ll_bitmap = findViewById(R.id.ll_bitmap);
        iv_bitmap = findViewById(R.id.iv_bitmap);
        textView = findViewById(R.id.upload_text);
        tv_prefile = findViewById(R.id.tv_prefile);
        tv_prefile.setVisibility(View.GONE);
        ll_bitmap.setVisibility(View.GONE);
        textView.setText("Uploading: 0%");
        EPaperDisplay.epdInd = 14;
        ed_url.setText(EPaperDisplay.getDisplays()[EPaperDisplay.epdInd].title);
    }

    /**
     * 扫描
     * @param view
     */
    public void onScan(View view)
    {
        startActivityForResult(
                new Intent(this, ScanningActivity.class),
                REQ_BLUETOOTH_CONNECTION);
    }

    /**
     * 上传
     * 通过view 预览得到电子屏幕展示样式
     * 后续要根据ui设计来做，现在没管线程问题，bitmap在主线程处理了
     */
    public void onUpdateQRImage(View view) {
        String result = ed_url.getText().toString();
        if (!TextUtils.isEmpty(result)){
            EPaperDisplay epd = EPaperDisplay.getDisplays()[EPaperDisplay.epdInd];
            Bitmap qr = QRCodeUtil.Create2DCode(result,epd.width,epd.height);
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) ll_bitmap.getLayoutParams();
            layoutParams.height = epd.height;
            layoutParams.width = epd.width;
            ll_bitmap.setLayoutParams(layoutParams);
            iv_bitmap.setImageBitmap(qr);
            tv_prefile.setVisibility(View.VISIBLE);
            ll_bitmap.setVisibility(View.VISIBLE);
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    Bitmap temp = Bitmap.createBitmap(
                            ll_bitmap.getWidth(), ll_bitmap.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(temp);
                    ll_bitmap.draw(canvas);
                    bitmap = EPaperPicture.createIndexedImage(temp,true, true);
                    BluetoothHelper.initialize(DemoActivity.btDevice, handler = new SocketHandler());
                    if (!BluetoothHelper.connect() || !handler.init(bitmap))
                    {
                        Toast.makeText(DemoActivity.this,"程序出错",Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            });
        }else{
            Toast.makeText(this,"输入url",Toast.LENGTH_LONG).show();
        }
    }


    @Override
    protected void onResume()
    {
        super.onResume();
    }

    @Override
    protected void onPause()
    {
        if (handler != null){
            BluetoothHelper.close();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy()
    {
        if (handler != null){
            BluetoothHelper.close();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed()
    {
        if (handler != null){
            BluetoothHelper.close();
        }
    }

    public void onCancel(View view)
    {
        onBackPressed();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == REQ_BLUETOOTH_CONNECTION)
        {
            if (resultCode == RESULT_OK)
            {
                btDevice = data.getParcelableExtra("DEVICE");
                text_blue.setText(btDevice.getName() + " (" + btDevice.getAddress() + ")");
            }
        }
    }

    // Uploaded data buffer
    //---------------------------------------------------------
    private static final int BUFF_SIZE = 256;
    private static byte[]    buffArr = new byte[BUFF_SIZE];
    private static int       buffInd;
    private static int       xLine;
    //---------------------------------------------------------
    //  Socket Handler
    //---------------------------------------------------------
    @SuppressLint("HandlerLeak")
    class SocketHandler extends Handler
    {
        private int   pxInd; // Pixel index in picture
        private int   stInd; // Stage index of uploading
        private int   dSize; // Size of uploaded data by LOAD command
        private int[] array; // Values of picture pixels

        public SocketHandler()
        {
            super();
        }

        // Converts picture pixels into selected pixel format
        // and sends EPDx command
        //-----------------------------------------------------
        private boolean init(Bitmap bmp)
        {
            int w = bmp.getWidth(); // Picture with
            int h = bmp.getHeight();// Picture height
            int epdInd = EPaperDisplay.epdInd;
            array = new int[w*h]; // Array of pixels
            int i = 0;            // Index of pixel in the array of pixels

            // Loading pixels into array
            //-------------------------------------------------

            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++, i++)
                    if(epdInd == 25)
                        array[i] = getVal_7color(bmp.getPixel(x, y));
                    else
                        array[i] = getVal(bmp.getPixel(x, y));

            pxInd = 0;
            xLine = 0;  //2.13inch
            stInd = 0;
            dSize = 0;

            buffInd = 2;                             // Size of command in bytes
            buffArr[0] = (byte)'I';                  // Name of command (Initialize)
            buffArr[1] = (byte)EPaperDisplay.epdInd; // Index of display

            return u_send(false);
        }

        // The function is executed after every "Ok!" response
        // obtained from esp32, which means a previous command
        // is complete and esp32 is ready to get the new one.
        //-----------------------------------------------------
        private boolean handleUploadingStage()
        {
            int epdInd = EPaperDisplay.epdInd;

            // 2.13 e-Paper display
            if (epdInd == 3)
            {
                if(stInd == 0) return u_line(0, 100);
                //-------------------------------------------------
                if(stInd == 1) return u_show();
            }

            // White-black e-Paper displays
            //-------------------------------------------------
            else if ((epdInd==0)||(epdInd==3)||(epdInd==6)||(epdInd==7)||(epdInd==9)||(epdInd==12)||
                    (epdInd==16)||(epdInd==19)||(epdInd==22)||(epdInd==26)||(epdInd==27)||(epdInd==28))
            {
                if(stInd == 0) return u_data(0,0,100);
                if(stInd == 1) return u_show();
            }

            // 7.5 colored e-Paper displays
            //-------------------------------------------------
            else if (epdInd>15 && epdInd < 22)
            {
                if(stInd == 0) return u_data(-1,0,100);
                if(stInd == 1) return u_show();
            }

            // 5.65f colored e-Paper displays
            //-------------------------------------------------
            else if (epdInd == 25)
            {
                if(stInd == 0) return u_data(-2,0,100);
                if(stInd == 1) return u_show();
            }

            // Other colored e-Paper displays
            //-------------------------------------------------
            else
            {
                if(stInd==0 && epdInd==23)return u_data(0,0,100);
                if(stInd == 0)return u_data((epdInd == 1)? -1 : 0,0,50);
                if(stInd == 1)return u_next();
                if(stInd == 2)return u_data(3,50,50);
                if(stInd == 3)return u_show();
            }

            return true;
        }

        // Returns the index of color in palette
        //-----------------------------------------------------
        public int getVal(int color)
        {
            int r = Color.red(color);
            int b = Color.blue(color);

            if((r == 0xFF) && (b == 0xFF)) return 1;
            if((r == 0x7F) && (b == 0x7F)) return 2;
            if((r == 0xFF) && (b == 0x00)) return 3;

            return 0;
        }

        // Returns the index of color in palette just for 5.65f e-Paper
        //-----------------------------------------------------
        public int getVal_7color(int color)
        {
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);

            if((r == 0x00) && (g == 0x00) && (b == 0x00)) return 0;
            if((r == 0xFF) && (g == 0xFF) && (b == 0xFF)) return 1;
            if((r == 0x00) && (g == 0xFF) && (b == 0x00)) return 2;
            if((r == 0x00) && (g == 0x00) && (b == 0xFF)) return 3;
            if((r == 0xFF) && (g == 0x00) && (b == 0x00)) return 4;
            if((r == 0xFF) && (g == 0xFF) && (b == 0x00)) return 5;
            if((r == 0xFF) && (g == 0x80) && (b == 0x00)) return 6;

            return 7;
        }

        // Sends command cmd
        //-----------------------------------------------------
        private boolean u_send(boolean next)
        {
            if (!BluetoothHelper.btThread.write(buffArr, buffInd))
                return false; // Command sending is failed

            if(next) stInd++; // Go to next stage if it is needed
            return true;      // Command is sent successful
        }

        // Next stage command
        //-----------------------------------------------------
        private boolean u_next()
        {
            buffInd = 1;           // Size of command in bytes
            buffArr[0] = (byte)'N';// Name of command (Next)

            pxInd = 0;
            return u_send(true);
        }

        // The finishing command
        //-----------------------------------------------------
        private boolean u_show()
        {
            buffInd = 1;           // Size of command in bytes
            buffArr[0] = (byte)'S';// Name of command (Show picture)

            // Return false if the SHOW command is not sent
            //-------------------------------------------------
            if (!u_send(true)) return false;

            // Otherwise exit the uploading activity.
            //-------------------------------------------------
            return true;
        }

        // Sends pixels of picture and shows uploading progress
        //-----------------------------------------------------
        private boolean u_load(int k1, int k2)
        {
            // Uploading progress message
            //-------------------------------------------------
            String x = "" + (k1 + k2*pxInd/array.length);
            if (x.length() > 5) x = x.substring(0, 5);
            handleUserInterfaceMessage(x);

            // Size of uploaded data
            //-------------------------------------------------
            dSize += buffInd;

            // Request message contains:
            //     data (maximum BUFF_SIZE bytes),
            //     size of uploaded data (4 bytes),
            //     length of data
            //     command name "LOAD"
            //-------------------------------------------------
            buffArr[0] = (byte)'L';

            // Size of packet
            //-------------------------------------------------
            buffArr[1] = (byte)(buffInd     );
            buffArr[2] = (byte)(buffInd >> 8);

            // Data size
            //-------------------------------------------------
            buffArr[3] = (byte)(dSize      );
            buffArr[4] = (byte)(dSize >>  8);
            buffArr[5] = (byte)(dSize >> 16);

            return u_send(pxInd >= array.length);
        }

        // Pixel format converting
        //-----------------------------------------------------
        private boolean u_data(int c, int k1, int k2)
        {
            buffInd = 6; // pixels' data offset

            if(c == -1)
            {
                while ((pxInd < array.length) && (buffInd + 1 < BUFF_SIZE))
                {
                    int v = 0;

                    for(int i = 0; i < 16; i += 2)
                    {
                        if (pxInd < array.length) v |= (array[pxInd] << i);
                        pxInd++;
                    }

                    buffArr[buffInd++] = (byte)(v     );
                    buffArr[buffInd++] = (byte)(v >> 8);
                }
            }
            else if(c == -2)
            {
                while ((pxInd < array.length) && (buffInd + 1 < BUFF_SIZE))
                {
                    int v = 0;

                    for(int i = 0; i < 16; i += 4)
                    {
                        if (pxInd < array.length) v |= (array[pxInd] << i);
                        pxInd++;
                    }

                    buffArr[buffInd++] = (byte)(v     );
                    buffArr[buffInd++] = (byte)(v >> 8);
                }
            }
            else
            {
                while ((pxInd < array.length) && (buffInd < BUFF_SIZE))
                {
                    int v = 0;

                    for (int i = 0; i < 8; i++)
                    {
                        if ((pxInd < array.length) && (array[pxInd] != c)) v |= (128 >> i);
                        pxInd++;
                    }

                    buffArr[buffInd++] = (byte)v;
                }
            }

            return u_load(k1, k2);
        }

        // Pixel format converting (2.13 e-Paper display)
        //-----------------------------------------------------
        private boolean u_line(int k1, int k2)
        {
            buffInd = 6; // pixels' data offset
            while ((pxInd < array.length) && (buffInd < 246))     // 15*16+6 ，16*8 = 128
            {
                int v = 0;

                for (int i = 0; (i < 8) && (xLine < 122); i++, xLine++){
                    if (array[pxInd++] != 0) v |= (128 >> i);
                }
                if(xLine >= 122 )xLine = 0;
                buffArr[buffInd++] = (byte)v;
            }
            return u_load(k1, k2);
        }

        //-------------------------------------------
        //  Handles socket message
        //-------------------------------------------
        public void handleMessage(android.os.Message msg)
        {
            // "Fatal error" event
            //-------------------------------------------------
            if (msg.what == BluetoothHelper.BT_FATAL_ERROR)
            {
                setResult(RESULT_CANCELED);
                finish();
            }

            // "Data is received" event
            //-------------------------------------------------
            else if (msg.what == BluetoothHelper.BT_RECEIVE_DATA)
            {
                // Convert data to string
                //---------------------------------------------
                String line = new String((byte[]) msg.obj, 0, msg.arg1);

                // If esp32 is ready for new command
                //---------------------------------------------
                if (line.contains("Ok!"))
                {
                    // Try to handle received data.
                    // If it's failed, restart the uploading
                    //-----------------------------------------
                    if (handleUploadingStage()) {
                        Log.e("zkh", "line:" + line);
                        return;
                    }
                }

                // Exit is the message is unknown
                //---------------------------------------------
                else if (!line.contains("Error!")) {
                    Log.e("zkh", "line:" + line);
                    return;
                }

                // Otherwise restart the uploading
                //-----------------------------------------
                BluetoothHelper.close();
                BluetoothHelper.connect();
                Log.e("zkh", "bitmap:" + bitmap.getWidth());
                handler.init(bitmap);
            }
        }
    }

    //---------------------------------------------------------
    //  User Interface Handler
    //---------------------------------------------------------
    public void handleUserInterfaceMessage(String msg)
    {
        runOnUiThread(new UserInterfaceHandler(msg));
    }

    private class UserInterfaceHandler implements Runnable
    {
        public String msg;

        public UserInterfaceHandler(String msg)
        {
            this.msg = "Uploading: " + msg + "%";
        }

        @Override
        public void run()
        {
            textView.setText(msg);
        }
    }


}
