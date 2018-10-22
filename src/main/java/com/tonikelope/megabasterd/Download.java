package com.tonikelope.megabasterd;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Long.valueOf;
import static java.lang.Thread.sleep;
import java.nio.channels.FileChannel;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import static java.util.concurrent.Executors.newCachedThreadPool;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import static java.util.logging.Level.SEVERE;
import java.util.logging.Logger;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.swing.JComponent;
import static com.tonikelope.megabasterd.MiscTools.*;
import static com.tonikelope.megabasterd.CryptTools.*;
import static com.tonikelope.megabasterd.DBTools.*;
import static com.tonikelope.megabasterd.MainPanel.*;
import java.io.BufferedInputStream;

/**
 *
 * @author tonikelope
 */
public final class Download implements Transference, Runnable, SecureSingleThreadNotifiable {

    public static final boolean VERIFY_CBC_MAC_DEFAULT = false;
    public static final boolean USE_SLOTS_DEFAULT = true;
    public static final int WORKERS_DEFAULT = 6;
    public static final boolean USE_MEGA_ACCOUNT_DOWN = false;
    public static final int CHUNK_SIZE_MULTI = 10;

    private final MainPanel _main_panel;
    private volatile DownloadView _view;
    private volatile ProgressMeter _progress_meter;
    private final Object _secure_notify_lock;
    private final Object _workers_lock;
    private final Object _chunkid_lock;
    private final Object _dl_url_lock;
    private final Object _turbo_proxy_lock;
    private boolean _notified;
    private final String _url;
    private final String _download_path;
    private String _file_name;
    private String _file_key;
    private Long _file_size;
    private String _file_pass;
    private String _file_noexpire;
    private final boolean _use_slots;
    private final int _slots;
    private final boolean _restart;
    private final ArrayList<ChunkDownloader> _chunkworkers;
    private final ExecutorService _thread_pool;
    private volatile boolean _exit;
    private volatile boolean _pause;
    private final ConcurrentLinkedQueue<Long> _partialProgressQueue;
    private volatile long _progress;
    private ChunkManager _chunkmanager;
    private String _last_download_url;
    private boolean _provision_ok;
    private boolean _finishing_download;
    private int _paused_workers;
    private File _file;
    private boolean _checking_cbc;
    private boolean _retrying_request;
    private Double _progress_bar_rate;
    private OutputStream _output_stream;
    private String _status_error_message;
    private boolean _status_error;
    private final ConcurrentLinkedQueue<Long> _rejectedChunkIds;
    private long _last_chunk_id_dispatched;
    private final MegaAPI _ma;
    private volatile boolean _canceled;
    private volatile boolean _error509;
    private volatile boolean _turbo_proxy_mode;

    public Download(MainPanel main_panel, MegaAPI ma, String url, String download_path, String file_name, String file_key, Long file_size, String file_pass, String file_noexpire, boolean use_slots, int slots, boolean restart) {

        _paused_workers = 0;
        _ma = ma;
        _last_chunk_id_dispatched = 0L;
        _status_error = false;
        _canceled = false;
        _status_error_message = null;
        _retrying_request = false;
        _checking_cbc = false;
        _finishing_download = false;
        _pause = false;
        _exit = false;
        _last_download_url = null;
        _provision_ok = true;
        _progress = 0L;
        _notified = false;
        _main_panel = main_panel;
        _url = url;
        _download_path = download_path;
        _file_name = file_name;
        _file_key = file_key;
        _file_size = file_size;
        _file_pass = file_pass;
        _file_noexpire = file_noexpire;
        _use_slots = use_slots;
        _slots = slots;
        _error509 = false;
        _turbo_proxy_mode = false;
        _restart = restart;
        _secure_notify_lock = new Object();
        _workers_lock = new Object();
        _chunkid_lock = new Object();
        _dl_url_lock = new Object();
        _turbo_proxy_lock = new Object();
        _chunkworkers = new ArrayList<>();
        _partialProgressQueue = new ConcurrentLinkedQueue<>();
        _rejectedChunkIds = new ConcurrentLinkedQueue<>();
        _thread_pool = newCachedThreadPool();
        _view = new DownloadView(this);
        _progress_meter = new ProgressMeter(this);
    }

    public Download(Download download) {

        _paused_workers = 0;
        _ma = download.getMa();
        _last_chunk_id_dispatched = 0L;
        _status_error = false;
        _canceled = false;
        _status_error_message = null;
        _retrying_request = false;
        _checking_cbc = false;
        _finishing_download = false;
        _pause = false;
        _exit = false;
        _last_download_url = null;
        _provision_ok = true;
        _progress = 0L;
        _notified = false;
        _main_panel = download.getMain_panel();
        _url = download.getUrl();
        _download_path = download.getDownload_path();
        _file_name = download.getFile_name();
        _file_key = download.getFile_key();
        _file_size = download.getFile_size();
        _file_pass = download.getFile_pass();
        _file_noexpire = download.getFile_noexpire();
        _use_slots = download.getMain_panel().isUse_slots_down();
        _slots = download.getMain_panel().getDefault_slots_down();
        _restart = true;
        _secure_notify_lock = new Object();
        _workers_lock = new Object();
        _chunkid_lock = new Object();
        _dl_url_lock = new Object();
        _turbo_proxy_lock = new Object();
        _chunkworkers = new ArrayList<>();
        _partialProgressQueue = new ConcurrentLinkedQueue<>();
        _rejectedChunkIds = new ConcurrentLinkedQueue<>();
        _thread_pool = newCachedThreadPool();
        _view = new DownloadView(this);
        _progress_meter = new ProgressMeter(this);

    }

    public void enableProxyTurboMode() {

        synchronized (_turbo_proxy_lock) {

            if (!_turbo_proxy_mode) {

                _turbo_proxy_mode = true;

                Download tthis = this;

                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {

                        synchronized (_workers_lock) {

                            getView().getSlots_spinner().setEnabled(false);

                            for (int t = getChunkworkers().size(); t <= Transference.MAX_WORKERS; t++) {

                                ChunkDownloader c = new ChunkDownloader(t, tthis);

                                _chunkworkers.add(c);

                                _thread_pool.execute(c);
                            }

                            getView().getSlots_spinner().setValue(Transference.MAX_WORKERS);

                            getView().getSlots_spinner().setEnabled(true);
                        }
                    }
                });
            }
        }
    }

    public ConcurrentLinkedQueue<Long> getRejectedChunkIds() {
        return _rejectedChunkIds;
    }

    public boolean isError509() {
        return _error509;
    }

    public void setError509(boolean error509) {
        _error509 = error509;
    }

    public Object getWorkers_lock() {
        return _workers_lock;
    }

    public boolean isChecking_cbc() {
        return _checking_cbc;
    }

    public boolean isRetrying_request() {
        return _retrying_request;
    }

    public boolean isExit() {
        return _exit;
    }

    public boolean isPause() {
        return _pause;
    }

    public void setExit(boolean exit) {
        _exit = exit;
    }

    public void setPause(boolean pause) {
        _pause = pause;
    }

    public ChunkManager getChunkmanager() {
        return _chunkmanager;
    }

    public String getFile_key() {
        return _file_key;
    }

    @Override
    public long getProgress() {
        return _progress;
    }

    public OutputStream getOutput_stream() {
        return _output_stream;
    }

    public File getFile() {
        return _file;
    }

    public ArrayList<ChunkDownloader> getChunkworkers() {

        synchronized (_workers_lock) {
            return _chunkworkers;
        }
    }

    public void setPaused_workers(int paused_workers) {
        _paused_workers = paused_workers;
    }

    public String getUrl() {
        return _url;
    }

    public String getDownload_path() {
        return _download_path;
    }

    @Override
    public String getFile_name() {
        return _file_name;
    }

    public String getFile_pass() {
        return _file_pass;
    }

    public String getFile_noexpire() {
        return _file_noexpire;
    }

    public boolean isUse_slots() {
        return _use_slots;
    }

    public int getSlots() {
        return _slots;
    }

    public void setLast_chunk_id_dispatched(long last_chunk_id_dispatched) {
        _last_chunk_id_dispatched = last_chunk_id_dispatched;
    }

    public boolean isProvision_ok() {
        return _provision_ok;
    }

    @Override
    public ProgressMeter getProgress_meter() {

        while (_progress_meter == null) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            }
        }

        return _progress_meter;
    }

    @Override
    public DownloadView getView() {

        while (_view == null) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
            }
        }

        return this._view;
    }

    @Override
    public MainPanel getMain_panel() {
        return _main_panel;
    }

    @Override
    public void start() {

        THREAD_POOL.execute(this);
    }

    @Override
    public void stop() {

        if (!isExit()) {
            getMain_panel().getDownload_manager().setPaused_all(false);
            Download.this.stopDownloader();
        }
    }

    @Override
    public void pause() {

        if (isPause()) {

            setPause(false);

            getMain_panel().getDownload_manager().setPaused_all(false);

            setPaused_workers(0);

            synchronized (_workers_lock) {

                for (ChunkDownloader downloader : getChunkworkers()) {

                    downloader.secureNotify();
                }
            }

            getView().resume();

        } else {

            setPause(true);

            getView().pause();
        }

        _main_panel.getDownload_manager().secureNotify();
    }

    public MegaAPI getMa() {
        return _ma;
    }

    @Override
    public void restart() {

        Download new_download = new Download(this);

        getMain_panel().getDownload_manager().getTransference_remove_queue().add(this);

        getMain_panel().getDownload_manager().getTransference_provision_queue().add(new_download);

        getMain_panel().getDownload_manager().secureNotify();
    }

    @Override
    public boolean isPaused() {
        return isPause();
    }

    @Override
    public boolean isStopped() {
        return isExit();
    }

    @Override
    public void checkSlotsAndWorkers() {

        if (!isExit()) {

            synchronized (_workers_lock) {

                int sl = getView().getSlots();

                int cworkers = getChunkworkers().size();

                if (sl != cworkers) {

                    if (sl > cworkers) {

                        startSlot();

                    } else {

                        stopLastStartedSlot();
                    }
                }
            }
        }
    }

    @Override
    public void close() {

        _main_panel.getDownload_manager().getTransference_remove_queue().add(this);

        _main_panel.getDownload_manager().secureNotify();
    }

    @Override
    public ConcurrentLinkedQueue<Long> getPartialProgress() {
        return _partialProgressQueue;
    }

    @Override
    public long getFile_size() {
        return _file_size;
    }

    @Override
    public void run() {

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {
                getView().getClose_button().setVisible(false);
            }
        });

        getView().printStatusNormal("Starting download, please wait...");

        try {

            if (!_exit) {

                String filename = _download_path + "/" + _file_name;

                _file = new File(filename);

                if (_file.getParent() != null) {
                    File path = new File(_file.getParent());

                    path.mkdirs();
                }

                if (!_file.exists()) {

                    getView().printStatusNormal("Starting download (retrieving MEGA temp link), please wait...");

                    _last_download_url = getMegaFileDownloadUrl(_url);

                    if (!_exit) {

                        _progress_bar_rate = MAX_VALUE / (double) _file_size;

                        filename = _download_path + "/" + _file_name;

                        _file = new File(filename + ".mctemp");

                        if (_file.exists()) {
                            getView().printStatusNormal("File exists, resuming download...");

                            long max_size = calculateMaxTempFileSize(_file.length());

                            if (max_size != _file.length()) {

                                Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Downloader truncating mctemp file {1} -> {2} ", new Object[]{Thread.currentThread().getName(), _file.length(), max_size});

                                getView().printStatusNormal("Truncating temp file...");

                                try (FileChannel out_truncate = new FileOutputStream(filename + ".mctemp", true).getChannel()) {
                                    out_truncate.truncate(max_size);
                                }
                            }

                            _progress = _file.length();

                            getView().updateProgressBar(_progress, _progress_bar_rate);

                        } else {
                            _progress = 0;
                            getView().updateProgressBar(0);
                        }

                        _output_stream = new BufferedOutputStream(new FileOutputStream(_file, (_progress > 0)));

                        _thread_pool.execute(getProgress_meter());

                        getMain_panel().getGlobal_dl_speed().attachTransference(this);

                        synchronized (_workers_lock) {

                            if (_use_slots) {

                                _chunkmanager = new ChunkManager(this);

                                _thread_pool.execute(_chunkmanager);

                                for (int t = 1; t <= _slots; t++) {
                                    ChunkDownloader c = new ChunkDownloader(t, this);

                                    _chunkworkers.add(c);

                                    _thread_pool.execute(c);
                                }

                                swingInvoke(
                                        new Runnable() {
                                    @Override
                                    public void run() {

                                        for (JComponent c : new JComponent[]{getView().getSlots_label(), getView().getSlots_spinner(), getView().getSlot_status_label()}) {

                                            c.setVisible(true);
                                        }
                                    }
                                });

                            } else {

                                ChunkDownloaderMono c = new ChunkDownloaderMono(this);

                                _chunkworkers.add(c);

                                _thread_pool.execute(c);

                                swingInvoke(
                                        new Runnable() {
                                    @Override
                                    public void run() {

                                        for (JComponent c : new JComponent[]{getView().getSlots_label(), getView().getSlots_spinner(), getView().getSlot_status_label()}) {

                                            c.setVisible(false);
                                        }
                                    }
                                });
                            }
                        }

                        getView().printStatusNormal(LabelTranslatorSingleton.getInstance().translate("Downloading file from mega ") + (_ma.getFull_email() != null ? "(" + _ma.getFull_email() + ")" : "") + " ...");

                        getMain_panel().getDownload_manager().secureNotify();

                        swingInvoke(
                                new Runnable() {
                            @Override
                            public void run() {

                                for (JComponent c : new JComponent[]{getView().getPause_button(), getView().getProgress_pbar()}) {

                                    c.setVisible(true);
                                }
                            }
                        });

                        secureWait();

                        _thread_pool.shutdown();

                        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Chunkdownloaders finished!", Thread.currentThread().getName());

                        getProgress_meter().setExit(true);

                        getProgress_meter().secureNotify();

                        try {

                            Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Waiting all threads to finish...", Thread.currentThread().getName());

                            _thread_pool.awaitTermination(MAX_WAIT_WORKERS_SHUTDOWN, TimeUnit.SECONDS);

                        } catch (InterruptedException ex) {
                            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                        }

                        if (!_thread_pool.isTerminated()) {

                            Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Closing thread pool ''mecag\u00fcen'' style...", Thread.currentThread().getName());

                            _thread_pool.shutdownNow();
                        }

                        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Downloader thread pool finished!", Thread.currentThread().getName());

                        getMain_panel().getGlobal_dl_speed().detachTransference(this);

                        _output_stream.close();

                        swingInvoke(
                                new Runnable() {
                            @Override
                            public void run() {

                                for (JComponent c : new JComponent[]{getView().getSpeed_label(), getView().getPause_button(), getView().getStop_button(), getView().getSlots_label(), getView().getSlots_spinner(), getView().getKeep_temp_checkbox()}) {

                                    c.setVisible(false);
                                }
                            }
                        });

                        getMain_panel().getDownload_manager().secureNotify();

                        if (_progress == _file_size) {
                            if (_file.length() != _file_size) {

                                throw new IOException("El tamaño del fichero es incorrecto!");
                            }

                            _file.renameTo(new File(filename));

                            String verify_file = selectSettingValue("verify_down_file");

                            if (verify_file != null && verify_file.equals("yes")) {
                                _checking_cbc = true;

                                getView().printStatusNormal("Waiting to check file integrity...");

                                _progress = 0;

                                getView().updateProgressBar(0);

                                getView().printStatusNormal("Checking file integrity, please wait...");

                                swingInvoke(
                                        new Runnable() {
                                    @Override
                                    public void run() {

                                        getView().getStop_button().setVisible(true);

                                        getView().getStop_button().setText(LabelTranslatorSingleton.getInstance().translate("CANCEL CHECK"));
                                    }
                                });

                                getMain_panel().getDownload_manager().getTransference_running_list().remove(this);

                                getMain_panel().getDownload_manager().secureNotify();

                                if (verifyFileCBCMAC(filename)) {

                                    getView().printStatusOK("File successfully downloaded! (Integrity check PASSED)");

                                } else if (!_exit) {

                                    getView().printStatusError("BAD NEWS :( File is DAMAGED!");

                                    _status_error = true;

                                } else {

                                    getView().printStatusOK("File successfully downloaded! (but integrity check CANCELED)");

                                    _status_error = true;

                                }

                                swingInvoke(
                                        new Runnable() {
                                    @Override
                                    public void run() {

                                        getView().getStop_button().setVisible(false);
                                    }
                                });

                            } else {

                                getView().printStatusOK("File successfully downloaded!");

                            }

                        } else if (_status_error) {

                            getView().hideAllExceptStatus();

                            getView().printStatusError(_status_error_message != null ? _status_error_message : "ERROR");

                        } else {

                            _canceled = true;

                            getView().hideAllExceptStatus();

                            getView().printStatusNormal("Download CANCELED!");
                        }

                    } else if (_status_error) {

                        getView().hideAllExceptStatus();

                        getView().printStatusError(_status_error_message != null ? _status_error_message : "ERROR");

                    } else {

                        _canceled = true;

                        getView().hideAllExceptStatus();

                        getView().printStatusNormal("Download CANCELED!");
                    }

                } else if (_status_error) {

                    getView().hideAllExceptStatus();

                    getView().printStatusError(_status_error_message != null ? _status_error_message : "ERROR");

                } else {

                    getView().hideAllExceptStatus();

                    getView().printStatusError("File already exists!");

                    _status_error = true;
                }

            } else if (_status_error) {

                getView().hideAllExceptStatus();

                getView().printStatusError(_status_error_message != null ? _status_error_message : "ERROR");

            } else {

                _canceled = true;

                getView().hideAllExceptStatus();

                getView().printStatusNormal("Download CANCELED!");
            }

        } catch (IOException ex) {

            getView().printStatusError("I/O ERROR " + ex.getMessage());

            _status_error = true;

            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);

        } catch (Exception ex) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
        }

        if (_file != null && !getView().isKeepTempFileSelected()) {
            _file.delete();

            long chunk_id = getChunkmanager().getLast_chunk_id_written() + 1;

            while (chunk_id < _last_chunk_id_dispatched) {
                File chunk_file = new File(getDownload_path() + "/" + getFile_name() + ".chunk" + String.valueOf(chunk_id++));

                if (chunk_file.exists()) {
                    chunk_file.delete();
                }
            }

            File parent_download_dir = new File(getDownload_path() + "/" + getFile_name()).getParentFile();

            while (!parent_download_dir.getAbsolutePath().equals(getDownload_path()) && parent_download_dir.listFiles().length == 0) {
                parent_download_dir.delete();
                parent_download_dir = parent_download_dir.getParentFile();
            }

            if (!(new File(getDownload_path() + "/" + getFile_name()).getParentFile().exists())) {

                getView().getOpen_folder_button().setEnabled(false);
            }
        }

        if (!_status_error) {

            try {
                deleteDownload(_url);
            } catch (SQLException ex) {
                Logger.getLogger(getClass().getName()).log(SEVERE, null, ex);
            }

        }

        getMain_panel().getDownload_manager().getTransference_running_list().remove(this);

        getMain_panel().getDownload_manager().getTransference_finished_queue().add(this);

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getMain_panel().getDownload_manager().getScroll_panel().remove(getView());

                getMain_panel().getDownload_manager().getScroll_panel().add(getView());
            }
        });

        getMain_panel().getDownload_manager().secureNotify();

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getClose_button().setVisible(true);

                if (_status_error || _canceled) {

                    getView().getRestart_button().setVisible(true);

                } else {

                    getView().getClose_button().setIcon(new javax.swing.ImageIcon(getClass().getResource("/images/icons8-ok-30.png")));
                }
            }
        });

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0}{1} Downloader: bye bye", new Object[]{Thread.currentThread().getName(), _file_name});
    }

    public void provisionIt(boolean retry) throws APIException {

        getView().printStatusNormal("Provisioning download, please wait...");

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getCopy_link_button().setVisible(true);
                getView().getOpen_folder_button().setVisible(true);
            }
        });

        String[] file_info;

        _provision_ok = false;

        try {
            if (_file_name == null) {

                file_info = getMegaFileMetadata(_url, getMain_panel().getView(), retry);

                if (file_info != null) {

                    _file_name = file_info[0];

                    _file_size = valueOf(file_info[1]);

                    _file_key = file_info[2];

                    if (file_info.length == 5) {

                        _file_pass = file_info[3];

                        _file_noexpire = file_info[4];
                    }

                    try {

                        insertDownload(_url, _ma.getFull_email(), _download_path, _file_name, _file_key, _file_size, _file_pass, _file_noexpire);

                        _provision_ok = true;

                    } catch (SQLException ex) {

                        Logger.getLogger(getClass().getName()).log(SEVERE, null, ex);

                        _status_error_message = "Error registering download: file is already downloading.";
                    }

                }

            } else if (_restart) {

                try {

                    insertDownload(_url, _ma.getFull_email(), _download_path, _file_name, _file_key, _file_size, _file_pass, _file_noexpire);

                    _provision_ok = true;

                } catch (SQLException ex) {

                    _status_error_message = "Error registering download: file is already downloading.";
                }
            } else {

                _provision_ok = true;
            }

        } catch (APIException ex) {

            throw ex;

        } catch (NumberFormatException ex) {

            _status_error_message = ex.getMessage();
        }

        if (!_provision_ok) {

            _status_error = true;

            if (_file_name != null) {
                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {

                        getView().getFile_name_label().setVisible(true);

                        getView().getFile_name_label().setText(truncateText(_download_path + "/" + _file_name, 100));

                        getView().getFile_name_label().setToolTipText(_download_path + "/" + _file_name);

                        getView().getFile_size_label().setVisible(true);

                        getView().getFile_size_label().setText(formatBytes(_file_size));
                    }
                });
            }

            getView().hideAllExceptStatus();

            if (_status_error_message == null) {

                _status_error_message = "PROVISION FAILED";
            }

            getView().printStatusError(_status_error_message);

            swingInvoke(
                    new Runnable() {
                @Override
                public void run() {

                    getView().getRestart_button().setVisible(true);
                }
            });

        } else {

            getView().printStatusNormal("Waiting to start...");

            swingInvoke(
                    new Runnable() {
                @Override
                public void run() {

                    getView().getFile_name_label().setVisible(true);

                    getView().getFile_name_label().setText(truncateText(_download_path + "/" + _file_name, 100));

                    getView().getFile_name_label().setToolTipText(_download_path + "/" + _file_name);

                    getView().getFile_size_label().setVisible(true);

                    getView().getFile_size_label().setText(formatBytes(_file_size));
                }
            });

        }
        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getClose_button().setVisible(true);
            }
        });

    }

    public void pause_worker() {

        synchronized (_workers_lock) {

            if (++_paused_workers == _chunkworkers.size() && !_exit) {

                getView().printStatusNormal("Download paused!");

                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {

                        getView().getPause_button().setText(LabelTranslatorSingleton.getInstance().translate("RESUME DOWNLOAD"));
                        getView().getPause_button().setEnabled(true);
                    }
                });

            }
        }
    }

    public void pause_worker_mono() {

        getView().printStatusNormal("Download paused!");

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {

                getView().getPause_button().setText(LabelTranslatorSingleton.getInstance().translate("RESUME DOWNLOAD"));
                getView().getPause_button().setEnabled(true);
            }
        });

    }

    public String getDownloadUrlForWorker() throws IOException {

        synchronized (_dl_url_lock) {

            if (_last_download_url != null && checkMegaDownloadUrl(_last_download_url)) {

                return _last_download_url;
            }

            boolean error;

            int conta_error = 0;

            String download_url;

            do {

                error = false;

                try {
                    if (findFirstRegex("://mega(\\.co)?\\.nz/", _url, 0) != null) {

                        download_url = _ma.getMegaFileDownloadUrl(_url);

                    } else {
                        download_url = MegaCrypterAPI.getMegaFileDownloadUrl(_url, _file_pass, _file_noexpire, _ma.getSid(), getMain_panel().getMega_proxy_server() != null ? (getMain_panel().getMega_proxy_server().getPort() + ":" + Bin2BASE64(("megacrypter:" + getMain_panel().getMega_proxy_server().getPassword()).getBytes()) + ":" + MiscTools.getMyPublicIP()) : null);
                    }

                    if (checkMegaDownloadUrl(download_url)) {

                        _last_download_url = download_url;

                    } else {

                        error = true;
                    }

                } catch (APIException ex) {

                    error = true;

                    try {
                        Thread.sleep(getWaitTimeExpBackOff(conta_error++) * 1000);
                    } catch (InterruptedException ex2) {
                        Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex2);
                    }
                }

            } while (error);

            return _last_download_url;

        }
    }

    public void startSlot() {

        if (!_exit) {

            synchronized (_workers_lock) {

                int chunk_id = _chunkworkers.size() + 1;

                ChunkDownloader c = new ChunkDownloader(chunk_id, this);

                _chunkworkers.add(c);

                try {

                    _thread_pool.execute(c);

                } catch (java.util.concurrent.RejectedExecutionException e) {
                    Logger.getLogger(getClass().getName()).log(Level.INFO, e.getMessage());
                }
            }
        }
    }

    public void stopLastStartedSlot() {

        if (!_exit) {

            synchronized (_workers_lock) {

                if (!_chunkworkers.isEmpty()) {

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getView().getSlots_spinner().setEnabled(false);
                        }
                    });

                    int i = _chunkworkers.size() - 1;

                    while (i >= 0) {

                        ChunkDownloader chundownloader = _chunkworkers.get(i);

                        if (!chundownloader.isExit()) {

                            chundownloader.setExit(true);

                            chundownloader.secureNotify();

                            _view.updateSlotsStatus();

                            break;

                        } else {

                            i--;
                        }
                    }
                }
            }
        }
    }

    public void stopThisSlot(ChunkDownloader chunkdownloader) {

        synchronized (_workers_lock) {

            if (_chunkworkers.remove(chunkdownloader) && !_exit) {

                if (!chunkdownloader.isExit()) {

                    _finishing_download = true;

                    if (_use_slots) {

                        swingInvoke(
                                new Runnable() {
                            @Override
                            public void run() {

                                getView().getSlots_spinner().setEnabled(false);

                                getView().getSlots_spinner().setValue((int) getView().getSlots_spinner().getValue() - 1);
                            }
                        });

                    }

                } else if (!_finishing_download && _use_slots) {

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getView().getSlots_spinner().setEnabled(true);
                        }
                    });

                }

                if (!_exit && isPause() && _paused_workers == _chunkworkers.size()) {

                    getView().printStatusNormal("Download paused!");

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getView().getPause_button().setText(LabelTranslatorSingleton.getInstance().translate("RESUME DOWNLOAD"));

                            getView().getPause_button().setEnabled(true);
                        }
                    });

                }

                if (_use_slots) {
                    getView().updateSlotsStatus();
                }
            }
        }

    }

    private boolean verifyFileCBCMAC(String filename) throws FileNotFoundException, Exception, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

        int[] int_key = bin2i32a(UrlBASE642Bin(_file_key));
        int[] iv = new int[]{int_key[4], int_key[5]};
        int[] meta_mac = new int[]{int_key[6], int_key[7]};
        int[] file_mac = {0, 0, 0, 0};
        int[] cbc_iv = {0, 0, 0, 0};

        byte[] byte_file_key = initMEGALinkKey(getFile_key());

        Cipher cryptor = genCrypter("AES", "AES/CBC/NoPadding", byte_file_key, i32a2bin(cbc_iv));

        try (BufferedInputStream is = new BufferedInputStream(new FileInputStream(filename))) {

            long chunk_id = 1L;
            long tot = 0L;
            byte[] byte_block = new byte[16];
            int[] int_block;
            int reads;
            int[] chunk_mac = new int[4];

            try {
                while (!_exit) {

                    long chunk_offset = ChunkManager.calculateChunkOffset(chunk_id, 1);

                    long chunk_size = ChunkManager.calculateChunkSize(chunk_id, this.getFile_size(), chunk_offset, 1);

                    ChunkManager.checkChunkID(chunk_id, this.getFile_size(), chunk_offset);

                    tot += chunk_size;

                    chunk_mac[0] = iv[0];
                    chunk_mac[1] = iv[1];
                    chunk_mac[2] = iv[0];
                    chunk_mac[3] = iv[1];

                    long conta_chunk = 0L;

                    while (conta_chunk < chunk_size && (reads = is.read(byte_block)) != -1) {

                        if (reads < byte_block.length) {

                            for (int i = reads; i < byte_block.length; i++) {
                                byte_block[i] = 0;
                            }
                        }

                        int_block = bin2i32a(byte_block);

                        for (int i = 0; i < chunk_mac.length; i++) {
                            chunk_mac[i] ^= int_block[i];
                        }

                        chunk_mac = bin2i32a(cryptor.doFinal(i32a2bin(chunk_mac)));

                        conta_chunk += reads;
                    }

                    for (int i = 0; i < file_mac.length; i++) {
                        file_mac[i] ^= chunk_mac[i];
                    }

                    file_mac = bin2i32a(cryptor.doFinal(i32a2bin(file_mac)));

                    setProgress(tot);

                    chunk_id++;

                }

            } catch (ChunkInvalidException e) {

            }

            int[] cbc = {file_mac[0] ^ file_mac[1], file_mac[2] ^ file_mac[3]};

            return (cbc[0] == meta_mac[0] && cbc[1] == meta_mac[1]);
        }
    }

    public void stopDownloader() {

        if (!_exit) {

            _exit = true;

            if (isRetrying_request()) {

                getView().stop("Retrying cancelled! " + truncateText(_url, 80));

            } else if (isChecking_cbc()) {

                getView().stop("Verification cancelled! " + truncateText(_file_name, 80));

            } else {

                getView().stop("Stopping download, please wait...");

                synchronized (_workers_lock) {

                    for (ChunkDownloader downloader : _chunkworkers) {

                        downloader.secureNotify();
                    }
                }

                secureNotify();
            }
        }
    }

    public void stopDownloader(String reason) {

        _status_error = true;

        _status_error_message = (reason != null ? LabelTranslatorSingleton.getInstance().translate("FATAL ERROR! ") + reason : LabelTranslatorSingleton.getInstance().translate("FATAL ERROR! "));

        stopDownloader();
    }

    public long calculateMaxTempFileSize(long size) {
        if (size > 3584 * 1024) {
            long reminder = (size - 3584 * 1024) % (1024 * 1024 * (isUse_slots() ? Download.CHUNK_SIZE_MULTI : 1));

            return reminder == 0 ? size : (size - reminder);
        } else {
            long i = 0, tot = 0;

            while (tot < size) {
                i++;
                tot += i * 128 * 1024;
            }

            return tot == size ? size : (tot - i * 128 * 1024);
        }
    }

    public String[] getMegaFileMetadata(String link, MainPanelView panel, boolean retry_request) throws APIException {

        String[] file_info = null;
        int retry = 0, error_code;
        boolean error;

        do {
            error = false;

            try {

                if (findFirstRegex("://mega(\\.co)?\\.nz/", link, 0) != null) {

                    file_info = _ma.getMegaFileMetadata(link);

                } else {

                    file_info = MegaCrypterAPI.getMegaFileMetadata(link, panel, getMain_panel().getMega_proxy_server() != null ? (getMain_panel().getMega_proxy_server().getPort() + ":" + Bin2BASE64(("megacrypter:" + getMain_panel().getMega_proxy_server().getPassword()).getBytes())) : null);
                }

            } catch (APIException ex) {

                error = true;

                _status_error = true;

                error_code = ex.getCode();

                if (Arrays.asList(FATAL_ERROR_API_CODES).contains(error_code)) {

                    stopDownloader(ex.getMessage() + " " + truncateText(link, 80));

                } else {

                    if (!retry_request) {

                        throw ex;
                    }

                    _retrying_request = true;

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getMain_panel().getView().getNew_download_menu().setEnabled(true);

                            getView().getStop_button().setVisible(true);

                            getView().getStop_button().setText(LabelTranslatorSingleton.getInstance().translate("CANCEL RETRY"));
                        }
                    });

                    for (long i = getWaitTimeExpBackOff(retry++); i > 0 && !_exit; i--) {
                        if (error_code == -18) {
                            getView().printStatusError(LabelTranslatorSingleton.getInstance().translate("File temporarily unavailable! (Retrying in ") + i + LabelTranslatorSingleton.getInstance().translate(" secs...)"));
                        } else {
                            getView().printStatusError("Mega/MC APIException error " + ex.getMessage() + LabelTranslatorSingleton.getInstance().translate(" (Retrying in ") + i + LabelTranslatorSingleton.getInstance().translate(" secs...)"));
                        }

                        try {
                            sleep(1000);
                        } catch (InterruptedException ex2) {
                        }
                    }
                }

            } catch (Exception ex) {

                if (!(ex instanceof APIException)) {
                    stopDownloader("Mega link is not valid! " + truncateText(link, 80));
                }
            }

        } while (!_exit && error);

        if (!_exit && !error) {

            swingInvoke(
                    new Runnable() {
                @Override
                public void run() {

                    getView().getStop_button().setText(LabelTranslatorSingleton.getInstance().translate("CANCEL DOWNLOAD"));
                    getView().getStop_button().setVisible(false);
                }
            });

        }

        return file_info;

    }

    public String getMegaFileDownloadUrl(String link) throws IOException, InterruptedException {

        String dl_url = null;
        int retry = 0, error_code;
        boolean error;

        do {
            error = false;

            try {
                if (findFirstRegex("://mega(\\.co)?\\.nz/", _url, 0) != null) {

                    dl_url = _ma.getMegaFileDownloadUrl(link);

                } else {
                    dl_url = MegaCrypterAPI.getMegaFileDownloadUrl(link, _file_pass, _file_noexpire, _ma.getSid(), getMain_panel().getMega_proxy_server() != null ? (getMain_panel().getMega_proxy_server().getPort() + ":" + Bin2BASE64(("megacrypter:" + getMain_panel().getMega_proxy_server().getPassword()).getBytes()) + ":" + MiscTools.getMyPublicIP()) : null);
                }

            } catch (APIException ex) {
                error = true;

                error_code = ex.getCode();

                if (Arrays.asList(FATAL_ERROR_API_CODES).contains(error_code)) {

                    stopDownloader(ex.getMessage() + " " + truncateText(link, 80));

                } else {

                    _retrying_request = true;

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getView().getStop_button().setVisible(true);

                            getView().getStop_button().setText(LabelTranslatorSingleton.getInstance().translate("CANCEL RETRY"));
                        }
                    });

                    for (long i = getWaitTimeExpBackOff(retry++); i > 0 && !_exit; i--) {
                        if (error_code == -18) {
                            getView().printStatusError("File temporarily unavailable! (Retrying in " + i + " secs...)");
                        } else {
                            getView().printStatusError("Mega/MC APIException error " + ex.getMessage() + " (Retrying in " + i + " secs...)");
                        }

                        try {
                            sleep(1000);
                        } catch (InterruptedException ex2) {
                        }
                    }
                }
            }

        } while (!_exit && error);

        if (!error) {

            swingInvoke(
                    new Runnable() {
                @Override
                public void run() {

                    getView().getStop_button().setText(LabelTranslatorSingleton.getInstance().translate("CANCEL DOWNLOAD"));
                    getView().getStop_button().setVisible(false);
                }
            });

        }

        return dl_url;
    }

    public long nextChunkId() throws ChunkInvalidException {

        synchronized (_chunkid_lock) {

            if (_main_panel.isExit()) {
                throw new ChunkInvalidException(null);
            }

            Long next_id;

            if ((next_id = _rejectedChunkIds.poll()) != null) {
                return next_id;
            } else {
                return ++_last_chunk_id_dispatched;
            }
        }

    }

    public void setStatus_error(boolean status_error) {
        _status_error = status_error;
    }

    public void rejectChunkId(long chunk_id) {
        _rejectedChunkIds.add(chunk_id);
    }

    @Override
    public void secureNotify() {
        synchronized (_secure_notify_lock) {

            _notified = true;

            _secure_notify_lock.notify();
        }
    }

    @Override
    public void secureWait() {

        synchronized (_secure_notify_lock) {
            while (!_notified) {

                try {
                    _secure_notify_lock.wait();
                } catch (InterruptedException ex) {
                    _exit = true;
                    Logger.getLogger(getClass().getName()).log(SEVERE, null, ex);
                }
            }

            _notified = false;
        }
    }

    @Override
    public void setProgress(long progress) {

        _progress = progress;

        getView().updateProgressBar(_progress, _progress_bar_rate);
    }

    @Override
    public boolean isStatusError() {
        return _status_error;
    }

}
