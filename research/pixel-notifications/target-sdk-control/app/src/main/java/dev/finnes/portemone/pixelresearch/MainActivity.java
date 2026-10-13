// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.pixelresearch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.util.Log;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.util.Collections;

public final class MainActivity extends Activity {
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  NotificationManager manager=getSystemService(NotificationManager.class);
  int notificationId=getIntent().getIntExtra("notificationId",3001);
  if (getIntent().getBooleanExtra("hideAll",false)) {
   manager.cancelAll(); Log.i("PixelCardLab","Canceled all own notifications"); finish(); return;
  }
  if (getIntent().hasExtra("delegate")) {
   if (android.os.Build.VERSION.SDK_INT >= 29) {
    String delegate=getIntent().getStringExtra("delegate");
    Log.i("PixelCardLab","Requested notification delegate="+delegate);
    if (delegate == null || delegate.isEmpty()) {
     manager.setNotificationDelegate(null);
    } else {
     try {
      android.content.pm.ApplicationInfo info=getPackageManager().getApplicationInfo(delegate,0);
      Log.i("PixelCardLab","Resolved delegate package="+info.packageName+" uid="+info.uid+" target="+info.targetSdkVersion);
      manager.setNotificationDelegate(delegate);
     } catch (android.content.pm.PackageManager.NameNotFoundException ex) {
      Log.e("PixelCardLab","Delegate package unavailable; preserving existing delegation",ex);
     }
    }
    Log.i("PixelCardLab","Notification delegate="+manager.getNotificationDelegate());
   }
   finish(); return;
  }
  NotificationChannel channel=new NotificationChannel("pixel_card_lab", "Pixel card lab", NotificationManager.IMPORTANCE_DEFAULT);
  channel.setSound(null,null); channel.enableVibration(false); channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
  manager.createNotificationChannel(channel);
  if (getIntent().getBooleanExtra("hide",false)) { manager.cancel(notificationId); finish(); return; }
  int target=getApplicationInfo().targetSdkVersion;
  RemoteViews small=new RemoteViews(getPackageName(),R.layout.compact_card);
  RemoteViews large=new RemoteViews(getPackageName(),R.layout.expanded_card);
  large.setTextViewText(R.id.target_label,"Target " + target + " · lab");
  large.setImageViewBitmap(R.id.code,renderQr(Math.round(182*getResources().getDisplayMetrics().density)));
  boolean expandedCompact=getIntent().getBooleanExtra("expandedCompact",false);
  int compactHeight=Math.max(48,Math.min(227,getIntent().getIntExtra("compactHeight",227)));
  if (expandedCompact) {
   small=new RemoteViews(getPackageName(),R.layout.expanded_card);
   small.setTextViewText(R.id.target_label,"Target " + target + " · " + compactHeight + "dp");
   int qrDp=Math.min(182,compactHeight-30);
   small.setImageViewBitmap(R.id.code,renderQr(Math.round(qrDp*getResources().getDisplayMetrics().density)));
   if (android.os.Build.VERSION.SDK_INT >= 31) {
    small.setViewLayoutHeight(R.id.expanded_root,compactHeight,android.util.TypedValue.COMPLEX_UNIT_DIP);
    small.setViewLayoutHeight(R.id.code,qrDp,android.util.TypedValue.COMPLEX_UNIT_DIP);
   }
  }
  Notification n=new Notification.Builder(this,"pixel_card_lab")
   .setSmallIcon(R.drawable.ic_pin).setContentTitle("Example Two · Pixel test")
   .setContentText("Target " + target + " compatibility control")
   .setCustomContentView(small).setCustomBigContentView(large)
   .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setOnlyAlertOnce(true)
   .setShowWhen(false).build();
  manager.notify(notificationId,n);
  Log.i("PixelCardLab","Posted id="+notificationId+" layout target="+target+" expanded=227dp expandedCompact="+expandedCompact+" compactHeight="+compactHeight);
  finish();
 }
 private Bitmap renderQr(int side) {
  try {
   BitMatrix matrix=new MultiFormatWriter().encode("1234567890123456",BarcodeFormat.QR_CODE,side,side,Collections.singletonMap(EncodeHintType.MARGIN,4));
   int[] pixels=new int[side*side];
   for(int y=0;y<side;y++) for(int x=0;x<side;x++) pixels[y*side+x]=matrix.get(x,y)?0xFF000000:0xFFFFFFFF;
   return Bitmap.createBitmap(pixels,side,side,Bitmap.Config.ARGB_8888);
  } catch(Exception e) { throw new IllegalStateException(e); }
 }
}
