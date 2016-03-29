package com.github.axet.wget;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.ex.DownloadError;
import com.github.axet.wget.info.ex.DownloadInterruptedError;

public class WGet {

	private DownloadInfo info;

	Direct d;

	// File targetFile;

	public interface HtmlLoader {
		/**
		 * some socket problem, retyring
		 * 
		 * @param delay
		 * @param e
		 */
		public void notifyRetry(int delay, Throwable e);

		/**
		 * start downloading
		 */
		public void notifyDownloading();

		/**
		 * document moved, relocating
		 */
		public void notifyMoved();
	}

	/**
	 * download with events control.
	 * 
	 * @param source
	 * @param target
	 * @deprecated use {@link WGet#WGet(DownloadInfo)} instead
	 */
	@Deprecated
	public WGet(URL source, File target) {
		create(source, target);
	}

	/**
     * application controlled download / resume.
     * 
     * @param info
     *            download info
     * @param targetFile
     *            target files
     */
    public WGet(DownloadInfo info) {
        this.info = info;
        
        create(info);
    }

	/**
	 * application controlled download / resume. you should specify targetfile
	 * name exactly. since you are choice resume / multipart download.
	 * application unable to control file name choice / creation.
	 * 
	 * @param info
	 *            download info
	 * @param pTargetFile
	 *            target files
	 * @deprecated use {@link WGet#WGet(DownloadInfo)} instead
	 */
	@Deprecated
	public WGet(DownloadInfo info, File pTargetFile) {
		this.info = info;
		File targetFile = pTargetFile;
		create();
	}

	void create(URL source, File target) {
		info = new DownloadInfo(source);
		info.extract();
		create(info);
	}

	void create(DownloadInfo info) {
		File targetFile = calcName(info);
		create();
	}

	void create() {
		d = createDirect();
	}

	Direct createDirect() {
		if (info.multipart()) {
			return new DirectMultipart(info);
		} else if (info.getRange()) {
			return new DirectRange(info);
		} else {
			return new DirectSingle(info);
		}
	}

	public static File calcName(URL source, File target) {
		DownloadInfo info = new DownloadInfo(source);
		info.extract();

		return calcName(info);
	}

	public static File calcName(DownloadInfo info) {
		// target -
		// 1) can point to directory.
		// - generate exclusive (1) name.
		// 2) to exisiting file
		// 3) to non existing file

		String name = null;

		name = info.getContentFilename();

		if (name == null)
			name = new File(info.getSource().getPath()).getName();

		try {
			name = URLDecoder.decode(name, "UTF-8");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		String nameNoExt = FilenameUtils.removeExtension(name);
		String ext = FilenameUtils.getExtension(name);

		File targetFile = null;

		if (info.getTargetFolder().isDirectory()) {
			targetFile = FileUtils.getFile(info.getTargetFolder(), name);
			int i = 1;
			while (targetFile.exists()) {
				targetFile = FileUtils.getFile(info.getTargetFolder(), nameNoExt + " (" + i + ")." + ext);
				i++;
			}
			info.setTargetFile(targetFile);
		} else {
			try {
				FileUtils.forceMkdir(new File(info.getTargetFile().getParent()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			targetFile = info.getTargetFile();
		}

		return targetFile;
	}

	public void download() {
		download(new AtomicBoolean(false), new Runnable() {
			@Override
			public void run() {
			}
		});
	}

	public void download(AtomicBoolean stop, Runnable notify) {
		d.download(stop, notify);
	}

	public DownloadInfo getInfo() {
		return info;
	}

	public static String getHtml(URL source) {
		return getHtml(source, new HtmlLoader() {
			@Override
			public void notifyRetry(int delay, Throwable e) {
			}

			@Override
			public void notifyDownloading() {
			}

			@Override
			public void notifyMoved() {
			}
		}, new AtomicBoolean(false));
	}

	public static String getHtml(DownloadInfo info) {
		return getHtml(info, new HtmlLoader() {
			@Override
			public void notifyRetry(int delay, Throwable e) {
			}

			@Override
			public void notifyDownloading() {
			}

			@Override
			public void notifyMoved() {
			}
		}, new AtomicBoolean(false));
	}

	public static String getHtml(final URL source, final HtmlLoader load, final AtomicBoolean stop) {
		return getHtml(new DownloadInfo(source), load, stop);
	}

	public static String getHtml(final DownloadInfo source, final HtmlLoader load, final AtomicBoolean stop) {
		String html = RetryWrap.wrap(stop, new RetryWrap.WrapReturn<String>() {
			DownloadInfo info = source;

			@Override
			public void proxy() {
				info.getProxy().set();
			}

			@Override
			public void retry(int delay, Throwable e) {
				load.notifyRetry(delay, e);
			}

			@Override
			public String download() throws IOException {
				HttpURLConnection conn = info.openConnection();

				RetryWrap.check(conn);

				InputStream is = conn.getInputStream();

				String enc = conn.getContentEncoding();

				if (enc == null) {
					Pattern p = Pattern.compile("charset=(.*)");
					Matcher m = p.matcher(conn.getHeaderField("Content-Type"));
					if (m.find()) {
						enc = m.group(1);
					}
				}

				if (enc == null)
					enc = "UTF-8";

				BufferedReader br = new BufferedReader(new InputStreamReader(is, enc));

				String line = null;

				StringBuilder contents = new StringBuilder();
				while ((line = br.readLine()) != null) {
					contents.append(line);
					contents.append("\n");

					if (stop.get())
						throw new DownloadInterruptedError("stop");
				}

				return contents.toString();
			}

			@Override
			public void moved(URL url) {
				DownloadInfo old = info;
				info = new DownloadInfo(url);
				info.setReferer(old.getReferer());

				load.notifyMoved();
			}

		});

		return html;
	}
}
