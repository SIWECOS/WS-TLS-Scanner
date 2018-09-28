/**
 *  SIWECOS-TLS-Scanner - A Webservice for the TLS-Scanner Module of TLS-Attacker
 *
 *  Copyright 2014-2017 Ruhr University Bochum / Hackmanit GmbH
 *
 *  Licensed under Apache License 2.0
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 */
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.rub.nds.siwecos.tls.ws;

import static de.rub.nds.siwecos.tls.ws.ScannerWS.LOGGER;
import de.rub.nds.tlsattacker.core.workflow.ParallelExecutor;
import de.rub.nds.tlsscanner.ScanJobExecutor;
import java.security.Security;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 *
 * @author Robert Merget <robert.merget@rub.de>
 */
public class PoolManager {

    protected static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger(PoolManager.class.getName());

    private ThreadPoolExecutor service;

    private int parallelProbeThreads = 64;

    private int probeThreads = 9;

    private PoolManager() {
        LOGGER.info("Adding BC as a Security Provider");
        Security.addProvider(new BouncyCastleProvider());
        LOGGER.info("Starting thread pool");
        service = new ThreadPoolExecutor(10, 10, 10, TimeUnit.MINUTES, new LinkedBlockingDeque<Runnable>());
    }

    public static PoolManager getInstance() {
        return PoolManagerHolder.INSTANCE;
    }

    private static class PoolManagerHolder {

        private static final PoolManager INSTANCE = new PoolManager();
    }

    public ThreadPoolExecutor getService() {
        return service;
    }

    public void setPoolSize(int poolsize) {
        boolean increasing = poolsize > service.getPoolSize();
        service.setCorePoolSize(poolsize);
        service.setMaximumPoolSize(poolsize);
        if (!increasing) {
            LOGGER.warn("You decreased the Threadpool Size! Changes take effect once all Tasks are completed or you restart the service!");
        }
    }

    public int getParallelProbeThreads() {
        return parallelProbeThreads;
    }

    public void setParallelProbeThreads(int parallelProbeThreads) {
        this.parallelProbeThreads = parallelProbeThreads;
    }

    public int getProbeThreads() {
        return probeThreads;
    }

    public void setProbeThreads(int probeThreads) {
        this.probeThreads = probeThreads;
    }
}
