package org.xidea.android.impl.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.xidea.android.Callback;
import org.xidea.android.Callback.CacheCallback;
import org.xidea.android.Callback.PrepareCallback;
import org.xidea.android.DrawableFactory;
import org.xidea.android.UIO;
import org.xidea.android.impl.DebugLog;
import org.xidea.android.impl.DefaultDrawableFactory;
import org.xidea.android.impl.http.HttpSupport;
import org.xidea.android.impl.http.HttpUtil;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Movie;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

public class ImageSupport {
	private LinkedHashMap<String, CacheInfo> queue = new LinkedHashMap<String, CacheInfo>(
			0, 0.75f, true);
	private Object queueLock = new Object();
	private int coreCapacity = 2 * 1024 * 1024;// 2M
	private int maxCapacity = 4 * 1024 * 1024;// 4M
	private int currentSize = 0;
	private static DrawableFactory DEFAULT_FACTORY = new DefaultDrawableFactory();
	private static int uioImageReservedId = 0xFFFFFFFF;// android.R.id.custom;
	public static final ImageSupport INSTANCE = new ImageSupport();

	private ImageSupport() {
	}

	public void bind(final ImageView view, final String url,
			DrawableFactory factory, final int fallbackResource,
			final Callback<Drawable> callback) {
		if (view == null) {
			DebugLog.fatal("invalid view:" + view);
			return;
		}
		if (factory == null) {
			factory = DEFAULT_FACTORY;
		}

		new Loader(view, url, factory, callback, fallbackResource)
				.doLoad();
	}

	public void clear(Context activity) {
		if (currentSize > coreCapacity) {
			synchronized (queueLock) {
				Iterator<Entry<String, CacheInfo>> it = queue.entrySet()
						.iterator();
				while (it.hasNext()) {
					Entry<String, CacheInfo> entry = it.next();
					boolean remove = false;
					CacheInfo info = entry.getValue();
					if (info.maybeContains(activity)) {// 需要清理的Activity，只要大于核心容量，
						if (currentSize > coreCapacity) {
							remove = true;
						}
					} else {// 对于其他没有指定需要清理的Activity，如果大于最大容量， 也需要删除。
						if (currentSize > maxCapacity) {
							remove = true;
						}
					}
					if (remove) {
						info.release();
						DebugLog.warn("remove cache: url:"+info.url+"currentSize:"
								+ currentSize);
						it.remove();
						currentSize -= info.size;
						if (currentSize <= coreCapacity) {
							break;
						}
					}
				}
			}
		}
	}

	public void clearAll() {
		DebugLog.warn("clear all");
		synchronized (queueLock) {
			for (CacheInfo inf : queue.values()) {
				inf.release();
			}
			queue.clear();
			currentSize = 0;
		}
	}

	public void removeCache(String path) {
		HttpSupport.INSTANCE.removeCache(path);
		synchronized (queueLock) {
			CacheInfo info = queue.remove(path);
			if (info != null) {
				currentSize -= info.size;
			}
		}
	}
	class Loader implements PrepareCallback<File, Drawable>,
			CacheCallback<Drawable>, Runnable {
		
		final ImageView view;
		final String url;
		final DrawableFactory factory;
		final Callback<Drawable> callback;

		private Drawable currentDrawable;
		
		private long cacheFileLength = 0;
		private CacheInfo hitedMemCache;
		private int fallbackResourceId;

		final static int STEP_CACHE_PREPARE = 0, STEP_CACHE = 2,
				STEP_CALLBACK_PREPARE = 3, STEP_CALLBACK = 4;
		int step;// 1:prepare,2:cache,3:prepare,4,callback

		Loader(ImageView view, String url,
				DrawableFactory factory, Callback<Drawable> callback, int resId) {
			this.view = view;
			this.factory = factory == null ? DEFAULT_FACTORY : factory;
			this.url = url;
			this.callback = callback;
			this.fallbackResourceId = resId;
		}

		void doLoad() {
			if (Looper.myLooper() != Looper.getMainLooper()) {
				DebugLog.warn("post bind:" + url + " to ui thread!!");
				view.post(new Runnable() {
					@Override
					public void run() {
						doLoad();
					}
				});
			} else if (url == null || url.length() == 0) {
				DebugLog.warn("invalid url:" + url);
				if(!useFallbackResource()){
					view.setImageDrawable(null);
				}
				return;
			} else {
				DebugLog.debug("bind:" + url + view);
				view.setTag(uioImageReservedId, url);
				tryMemeryCache();
				if (hitedMemCache == null) {
					updateImageDrawable(view, factory.getLoadingDrawable(view));
					currentDrawable = null;
					UIO.get(this, url);
				}
			}
		}

		public void addCache(String url,Object cache) {
			if (cache != null) {
				synchronized (queueLock) {
					CacheInfo info = new CacheInfo(url,factory, cache, view);
					queue.put(url + '#' + System.identityHashCode(factory),
							info);
					int size = info.size;
					currentSize += size;
				}
				if (currentSize > maxCapacity) {
					DebugLog.warn("memmery clear on new image ! count:"+queue.size());
					clear(null);
				}
			}
		}

		private void tryMemeryCache() {
			if (hitedMemCache == null) {
				CacheInfo info;
				synchronized (queueLock) {
					info = queue.get(url + '#' + factory.hashCode());
				}
				if (info != null && info.cache != null
						&& factory.equals(info.factory)) {
					// 跳过其他判断，直接绘制内容了
					DebugLog.debug("use old image!" + url
							+ "\nqueue info:count:" + queue.size() + ";memery:"
							+ currentSize / 1024f / 1024f + "M\n");
					hitedMemCache = info;
					view.post(this);
				}
			}
		}

		public void run() {
			hitedMemCache.attach(view);
			Object cache = hitedMemCache.cache;

			boolean valid = url.equals(view.getTag(uioImageReservedId));
			if (valid && cache != null) {
				if (cache instanceof Bitmap) {
					updateImageDrawable(view,
							factory.createDrawable((Bitmap) cache));
				} else {
					updateImageDrawable(view,
							factory.createDrawable((Movie) cache));
				}
			}

		}

		public boolean cache(Drawable drawable) {
			if (drawable != null) {
				boolean valid = url.equals(view.getTag(uioImageReservedId));
				if (valid) {
					updateImageDrawable(view, drawable);
				}
				if (callback != null) {
					callback.callback(drawable);
				}
				// 不信任 默认缓存
				// return true;
			}
			this.step = STEP_CACHE;
			return hitedMemCache != null;
		}

		public void callback(Drawable drawable) {
			boolean valid = url.equals(view.getTag(uioImageReservedId));
			if (valid) {
				if (drawable == null) {
					useFallbackResource();
				} else {
					updateImageDrawable(view, drawable);
				}
			}
			if (callback != null) {
				callback.callback(drawable);
			}

			this.step = STEP_CALLBACK;
		}

		@Override
		public Object prepare(File file) {
			boolean isCachePrepare = step < STEP_CACHE;
			if (isCachePrepare) {
				cacheFileLength = file == null ? 0 : file.length();
				tryMemeryCache();
			} else if (file != null) {
				if (cacheFileLength == file.length()) {// 文件大小一样就放弃更新，减少图片解码开销
					return null;
				}
			}
			try {
				if (hitedMemCache == null && file != null && file.exists()) {
					DebugLog.info("parse on "+(isCachePrepare?"cache:":"callback:")+file.getName());
					Object data = ImageUtil.createMediaContent(new FileInputStream(
							file), view, null, null);
					if (data != null) {
						// 只要prepare了，bitmap的控制权就自动交给了系统。
						if (data instanceof Bitmap) {
							final Bitmap bitmap = factory
									.prepare((Bitmap) data);
							addCache(url,bitmap);
							return factory.createDrawable(bitmap);
						} else if (data instanceof Movie) {
							final Movie cache = factory.prepare((Movie) data);
							addCache(url,cache);
							return factory.createDrawable(cache);
						} else {
							throw new RuntimeException(
									"unknow media type! bitmap || movie is required!!");
						}
					}
					return null;

				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				this.step = isCachePrepare ? STEP_CACHE_PREPARE
						: STEP_CALLBACK_PREPARE;
			}
			return null;
		}


		private void updateImageDrawable(ImageView view, Drawable drawable) {
			Drawable old = view.getDrawable();
			if (old instanceof DefaultDrawableFactory.SafeBitmapDrawable) {
				ImageUtil.release(((BitmapDrawable) old).getBitmap());
			}
			this.currentDrawable = drawable;
			//DebugLog.info("set drawable:"+url+""+view+drawable);
			if(drawable instanceof MovieDrawable){
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
					view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
				}
			}
			view.setImageDrawable(drawable);
		}

		private boolean useFallbackResource() {
			if (currentDrawable == null && fallbackResourceId >= 0) {
				updateImageDrawable(view, null);
				view.setImageResource(fallbackResourceId);
				return true;
			}else{
				return false;
			}
		}

		public void error(Throwable ex, boolean callbackError) {
			if (DebugLog.isDebug()) {
				UIFacade.getInstance().shortTips("图片装载失败：" + ex);
			}
			useFallbackResource();
			if (callback != null) {
				callback.error(ex, callbackError);
			} else {
				ex.printStackTrace();
			}
		}

	}

	private static class CacheInfo {
		final DrawableFactory factory;
		final int size;
		final Object cache;
		final String url;
		int activityMask;
		

		CacheInfo(String url,DrawableFactory factory, Object bitmap, ImageView view) {
			this.url = url;
			this.factory = factory;
			this.cache = bitmap;
			if (bitmap instanceof Bitmap) {
				this.size = factory.getSize((Bitmap) bitmap);
				ImageUtil.retain((Bitmap) bitmap);
			} else {
				this.size = factory.getSize((Movie) bitmap);
			}
			this.activityMask = Math.abs(System.identityHashCode(view
					.getContext()));
		}

		public void release() {
			if (cache instanceof Bitmap) {
				// 不能莽撞清理
				ImageUtil.release((Bitmap) cache);
			}
		}

		boolean maybeContains(Context activity) {
			int hash = Math.abs(System.identityHashCode(activity));
			return hash == (hash & activityMask);
		}

		void attach(ImageView view) {
			int hash = Math.abs(System.identityHashCode(view.getContext()));
			this.activityMask |= hash;

		}
	}

}
