package tinkerdemo5.com.example.lxr.update;

import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.gson.Gson;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import tinkerdemo5.com.example.lxr.update.bean.Ver1;

public class UpdateManager {
	
	private ProgressBar mProgressBar;
	private Dialog mDownloadDialog;
	
	private String mSavePath;
	private int mProgress;

	private boolean mIsCancel = false;
	
	private static final int DOWNLOADING = 1;
	private static final int DOWNLOAD_FINISH = 2;

	private String mVersion_code;
	private String mVersion_name;
	private String mVersion_desc;
	private String mVersion_path;

	//注意:这里放置在Tomact上的一个json数据
	private static final String PATH = "http://169.254.141.236:8080/version.json";

	//创建一个上下文对象
	private Context mContext;

	public UpdateManager(Context context) {
		mContext = context;
	}

	/*
 	* 检测软件是否需要更新
 	*/
	public void checkUpdate() {
		//创建okhttpClient对象
		OkHttpClient okHttpClient = new OkHttpClient();
		Request request = new Request.Builder()
				.url(PATH)
				.build();

		//创建一个Call对象,参数就是Request.
		Call call = okHttpClient.newCall(request);
		//使用call对象,进行异步请求.
		call.enqueue(new Callback() {
			@Override//请求失败的时候调用该方法
			public void onFailure(Call call, IOException e) {
			}
			@Override//请求成功时调用该方法
			public void onResponse(Call call, Response response) throws IOException {
				//通过参数response,我们来拿到网上获取的数据,更加数据类型,调用对应的方法
				String text = response.body().string();
				//主线程不能做耗时操作,子线程不能更新UI,通过handler来完成数据的传递
				Message obtain = Message.obtain();
				obtain.obj=text;
				mGetVersionHandler.sendMessage(obtain);
			}
		});
	}

	private Handler mGetVersionHandler = new Handler(){
		public void handleMessage(Message msg) {
			//对数据打印,看有没有得到数据
			String text = (String) msg.obj;
			System.out.println(text);
			//对数据进行json解析
			Gson gson = new Gson();
			Ver1 ver = gson.fromJson(text, Ver1.class);
			try {
				mVersion_code =ver.version_code ;
				mVersion_name = ver.version_name;
				mVersion_desc = ver.version_desc;
				mVersion_path = ver.version_path;
				//进行判断,与本地版本相比,是否需要更新
				if (isUpdate()){
					Toast.makeText(mContext, "需要更新", Toast.LENGTH_SHORT).show();
					// 显示提示更新对话框
					showNoticeDialog();
				} else{
					Toast.makeText(mContext, "已是最新版本", Toast.LENGTH_SHORT).show();
				}
				
			} catch (Exception e){
				e.printStackTrace();
			}
		};
	};
	
	private Handler mUpdateProgressHandler = new Handler(){
		public void handleMessage(Message msg) {
			switch (msg.what){
			case DOWNLOADING:
				// 设置进度条
				mProgressBar.setProgress(mProgress);
				break;
			case DOWNLOAD_FINISH:
				// 隐藏当前下载对话框
				mDownloadDialog.dismiss();
				// 安装 APK 文件
				installAPK();
			}
		};
	};

	/*
	 * 与本地版本比较判断是否需要更新
	 */
	protected boolean isUpdate() {
		//把服务器端得到版本号,改变为整型
		int serverVersion = Integer.parseInt(mVersion_code);

		//本地默认版本为1;
		int localVersion = 1;
		
		try {
			//获取本地版本号,参数 1:要获取程序版本号的包名(需要变动)  2:一般为0
			localVersion = mContext.getPackageManager().getPackageInfo("tinkerdemo5.com.example.lxr", 0).versionCode;
			Log.d("UpdateManager", "localVersion:" + localVersion);
			//System.out.println(localVersion);
		} catch (NameNotFoundException e) {
			e.printStackTrace();
		}
		//进行判断,服务器apk版本是否大于本地apk版本
		if (serverVersion > localVersion)
			return true;
		else
			return false;
	}

	/*
	 * 有更新时显示提示对话框
	 */
	protected void showNoticeDialog() {
		Builder builder = new Builder(mContext);
		builder.setTitle("提示");
		//更新内容
		String message = "软件有更新，要下载安装吗？\n" + mVersion_desc;
		builder.setMessage(message);

		builder.setPositiveButton("更新", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				// 隐藏当前对话框
				dialog.dismiss();
				// 显示下载对话框
				showDownloadDialog();
			}
		});

		builder.setNegativeButton("下次再说", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				// 隐藏当前对话框
				dialog.dismiss();
			}
		});

		builder.create().show();
	}

	/*
	 * 显示正在下载对话框
	 */
	protected void showDownloadDialog() {
		Builder builder = new Builder(mContext);
		builder.setTitle("下载中");
		View view = LayoutInflater.from(mContext).inflate(R.layout.dialog_progress, null);
		mProgressBar = (ProgressBar) view.findViewById(R.id.id_progress);
		builder.setView(view);
		
		builder.setNegativeButton("取消", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				// 隐藏当前对话框
				dialog.dismiss();
				// 设置下载状态为取消
				mIsCancel = true;
			}
		});
		
		mDownloadDialog = builder.create();
		mDownloadDialog.show();
		
		// 下载文件
		downloadAPK();
	}

	/*
	 * 开启新线程下载文件
	 */
	private void downloadAPK() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try{
					if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
						String sdPath = Environment.getExternalStorageDirectory() + "/";
						mSavePath = sdPath + "ycfownload";
						
						File dir = new File(mSavePath);
						if (!dir.exists())
							dir.mkdir();
						
						// 下载文件
						HttpURLConnection conn = (HttpURLConnection) new URL(mVersion_path).openConnection();
						conn.connect();
						InputStream is = conn.getInputStream();
						int length = conn.getContentLength();
						
						File apkFile = new File(mSavePath, mVersion_name);
						FileOutputStream fos = new FileOutputStream(apkFile);
						
						int count = 0;
						byte[] buffer = new byte[1024];
						while (!mIsCancel){
							int numread = is.read(buffer);
							count += numread;
							// 计算进度条的当前位置
							mProgress = (int) (((float)count/length) * 100);
							// 更新进度条
							mUpdateProgressHandler.sendEmptyMessage(DOWNLOADING);
							
							// 下载完成
							if (numread < 0){
								mUpdateProgressHandler.sendEmptyMessage(DOWNLOAD_FINISH);
								break;
							}
							fos.write(buffer, 0, numread);
						}
						fos.close();
						is.close();
					}
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		}).start();
	}

	/*
	 * 下载到本地后执行安装
	 */
	protected void installAPK() {
		File apkFile = new File(mSavePath, mVersion_name);
		if (!apkFile.exists())
			return;
		
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
		mContext.startActivity(intent);
	}

}
