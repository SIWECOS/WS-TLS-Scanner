/**
 *  SIWECOS-TLS-Scanner - A Webservice for the TLS-Scanner Module of TLS-Attacker
 *
 *  Copyright 2014-2017 Ruhr University Bochum / Hackmanit GmbH
 *
 *  Licensed under Apache License 2.0
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 */
package de.rub.nds.siwecos.tls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.rub.nds.siwecos.tls.constants.ScanType;
import de.rub.nds.siwecos.tls.json.CertificateTestInfo;
import de.rub.nds.siwecos.tls.json.CiphersuitesTestInfo;
import de.rub.nds.siwecos.tls.json.CollectedScanResult;
import de.rub.nds.siwecos.tls.json.DateTestInfo;
import de.rub.nds.siwecos.tls.json.ErrorTestInfo;
import de.rub.nds.siwecos.tls.json.HashTestInfo;
import de.rub.nds.siwecos.tls.json.HostTestInfo;
import de.rub.nds.siwecos.tls.json.ScanResult;
import de.rub.nds.siwecos.tls.json.TestResult;
import de.rub.nds.siwecos.tls.json.TranslateableMessage;
import de.rub.nds.siwecos.tls.json.TestInfo;
import de.rub.nds.siwecos.tls.ws.DebugOutput;
import de.rub.nds.siwecos.tls.ws.PoolManager;
import de.rub.nds.siwecos.tls.ws.ScanRequest;
import de.rub.nds.tlsattacker.attacks.constants.EarlyCcsVulnerabilityType;
import de.rub.nds.tlsattacker.core.config.delegate.ClientDelegate;
import de.rub.nds.tlsattacker.core.config.delegate.GeneralDelegate;
import de.rub.nds.tlsattacker.core.config.delegate.StarttlsDelegate;
import de.rub.nds.tlsattacker.core.constants.CipherSuite;
import de.rub.nds.tlsattacker.core.constants.HashAlgorithm;
import de.rub.nds.tlsattacker.core.constants.StarttlsType;
import de.rub.nds.tlsattacker.core.workflow.NamedThreadFactory;
import de.rub.nds.tlsattacker.core.workflow.ParallelExecutor;
import de.rub.nds.tlsscanner.MultiThreadedScanJobExecutor;
import de.rub.nds.tlsscanner.ScanJobExecutor;
import de.rub.nds.tlsscanner.TlsScanner;
import de.rub.nds.tlsscanner.config.ScannerConfig;
import de.rub.nds.tlsscanner.constants.ProbeType;
import de.rub.nds.tlsscanner.constants.ScannerDetail;
import de.rub.nds.tlsscanner.probe.BleichenbacherProbe;
import de.rub.nds.tlsscanner.probe.CertificateProbe;
import de.rub.nds.tlsscanner.probe.CiphersuiteOrderProbe;
import de.rub.nds.tlsscanner.probe.CiphersuiteProbe;
import de.rub.nds.tlsscanner.probe.CompressionsProbe;
import de.rub.nds.tlsscanner.probe.EarlyCcsProbe;
import de.rub.nds.tlsscanner.probe.ExtensionProbe;
import de.rub.nds.tlsscanner.probe.HeartbleedProbe;
import de.rub.nds.tlsscanner.probe.InvalidCurveProbe;
import de.rub.nds.tlsscanner.probe.PaddingOracleProbe;
import de.rub.nds.tlsscanner.probe.PoodleProbe;
import de.rub.nds.tlsscanner.probe.ProtocolVersionProbe;
import de.rub.nds.tlsscanner.probe.SniProbe;
import de.rub.nds.tlsscanner.probe.Tls13Probe;
import de.rub.nds.tlsscanner.probe.TlsPoodleProbe;
import de.rub.nds.tlsscanner.probe.TlsProbe;
import de.rub.nds.tlsscanner.probe.certificate.CertificateReport;
import de.rub.nds.tlsscanner.report.SiteReport;
import de.rub.nds.tlsscanner.report.after.AfterProbe;
import de.rub.nds.tlsscanner.report.after.FreakAfterProbe;
import de.rub.nds.tlsscanner.report.after.LogjamAfterprobe;
import de.rub.nds.tlsscanner.report.after.Sweet32AfterProbe;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.Security;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 *
 * @author Robert Merget <robert.merget@rub.de>
 */
public class TlsScannerCallback implements Runnable {

    protected static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger(TlsScannerCallback.class
            .getName());

    private final ScanRequest request;

    private final DebugOutput debugOutput;

    private final ScanType type;

    public TlsScannerCallback(ScanRequest request, ScanType type, DebugOutput debugOutput) {
        this.request = request;
        this.debugOutput = debugOutput;
        this.type = type;
    }

    private String callbackUrlsToId(String[] urls) {
        StringBuilder builder = new StringBuilder();
        for (String s : urls) {
            builder.append(s);
        }
        return "" + Math.abs(builder.toString().hashCode());
    }

    @Override
    public void run() {

        Thread.currentThread().setName(Thread.currentThread().getName() + "-" + request.getUrl());
        Security.addProvider(new BouncyCastleProvider());
        debugOutput.setLeftQueueAt(System.currentTimeMillis());
        debugOutput.setScanStartedAt(System.currentTimeMillis());
        debugOutput.setTimeInQueue(debugOutput.getLeftQueueAt() - debugOutput.getEnteredQueueAt());
        String id = callbackUrlsToId(request.getCallbackurls());
        LOGGER.info("Scanning: " + request.getUrl() + " - " + id + " for " + type);
        for (String s : request.getCallbackurls()) {
            LOGGER.info("\tCallbackUrls: " + s);
        }
        if (type != ScanType.MAIL) {
            ScanResult result = getScanResult(type, id, request);
            answer(result);
        } else {
            List<ScanResult> scanResultList = new LinkedList<>();
            try {
                for (ScanType type : ScanType.values()) {
                    if (type == ScanType.MAIL || type == ScanType.TLS) {
                        continue;
                    } else {
                        ScanResult result = getScanResult(type, id, request);
                        scanResultList.add(result);
                    }
                }
                answer(new CollectedScanResult(type.name(), false, null, 0, scanResultList));
            } catch (Exception E) {
                answer(new CollectedScanResult(type.name(), true, new TranslateableMessage("REPORT_CONSTRUCTION",
                        new ErrorTestInfo(E.getMessage())), 0, scanResultList));
            }
        }

    }

    public ScanResult getScanResult(ScanType type, String id, ScanRequest request) {
        try {
            ScannerConfig scannerConfig = new ScannerConfig(new GeneralDelegate());
            scannerConfig.setDangerLevel(request.getDangerLevel());
            scannerConfig.setScanDetail(ScannerDetail.QUICK);
            scannerConfig.setNoProgressbar(true);
            int port = 443;
            StarttlsDelegate starttlsDelegate = (StarttlsDelegate) scannerConfig.getDelegate(StarttlsDelegate.class);

            switch (type) {
                case TLS:
                    port = 443;
                    break;
                case IMAP_TLS:
                    starttlsDelegate.setStarttlsType(StarttlsType.IMAP);
                    port = 143;
                    break;
                case IMAPS_TLS:
                    port = 993;
                    break;
                case POP3_TLS:
                    starttlsDelegate.setStarttlsType(StarttlsType.POP3);
                    port = 110;
                    break;
                case POP3S_TLS:
                    port = 995;
                    break;
                case SMTP_TLS:
                    starttlsDelegate.setStarttlsType(StarttlsType.SMTP);
                    port = 25;
                    break;
                case SMTP_MSA_TLS:
                    starttlsDelegate.setStarttlsType(StarttlsType.SMTP);
                    port = 587;
                    break;
                case SMTPS_TLS:
                    port = 465;
                    break;
            }

            ClientDelegate delegate = (ClientDelegate) scannerConfig.getDelegate(ClientDelegate.class);
            delegate.setHost(request.getUrl() + ":" + port);
            LOGGER.info("Scanning: " + delegate.getHost() + " for " + type);
            ParallelExecutor executor = new ParallelExecutor(PoolManager.getInstance().getParallelProbeThreads(), 3,
                    new NamedThreadFactory("" + id));
            List<TlsProbe> phaseOneList = new LinkedList<>();
            List<TlsProbe> phaseTwoList = new LinkedList<>();
            List<AfterProbe> afterList = new LinkedList<>();
            // phaseOneList.add(new CommonBugProbe(scannerConfig, executor));
            phaseOneList.add(new SniProbe(scannerConfig, executor));
            phaseOneList.add(new CompressionsProbe(scannerConfig, executor));
            // phaseOneList.add(new NamedCurvesProbe(scannerConfig, executor));
            phaseOneList.add(new CertificateProbe(scannerConfig, executor));
            phaseOneList.add(new ProtocolVersionProbe(scannerConfig, executor));
            phaseOneList.add(new CiphersuiteProbe(scannerConfig, executor));
            phaseOneList.add(new CiphersuiteOrderProbe(scannerConfig, executor));
            phaseOneList.add(new ExtensionProbe(scannerConfig, executor));
            phaseOneList.add(new Tls13Probe(scannerConfig, executor));
            // phaseOneList.add(new TokenbindingProbe(scannerConfig, executor));
            // phaseOneList.add(new HttpHeaderProbe(scannerConfig, executor));
            // phaseTwoList.add(new ResumptionProbe(scannerConfig, executor));
            // phaseTwoList.add(new RenegotiationProbe(scannerConfig,
            // executor));
            phaseTwoList.add(new HeartbleedProbe(scannerConfig, executor));
            phaseTwoList.add(new PaddingOracleProbe(scannerConfig, executor));
            phaseTwoList.add(new BleichenbacherProbe(scannerConfig, executor));
            phaseTwoList.add(new PoodleProbe(scannerConfig, executor));
            phaseTwoList.add(new TlsPoodleProbe(scannerConfig, executor));
            // phaseTwoList.add(new Cve20162107Probe(scannerConfig, executor));
            phaseTwoList.add(new InvalidCurveProbe(scannerConfig, executor));
            // phaseTwoList.add(new DrownProbe(scannerConfig, executor));
            phaseTwoList.add(new EarlyCcsProbe(scannerConfig, executor));
            // phaseTwoList.add(new MacProbe(scannerConfig, executor));
            afterList.add(new Sweet32AfterProbe());
            afterList.add(new FreakAfterProbe());
            afterList.add(new LogjamAfterprobe());
            ScanJobExecutor scanJobExecutor = new MultiThreadedScanJobExecutor(PoolManager.getInstance()
                    .getProbeThreads(), id);
            TlsScanner scanner = new TlsScanner(scannerConfig, scanJobExecutor, executor, phaseOneList, phaseTwoList,
                    afterList);
            SiteReport report = scanner.scan();
            executor.shutdown();
            scanJobExecutor.shutdown();
            ScanResult result = reportToScanResult(report, type);
            LOGGER.info("Finished scanning: " + request.getUrl() + " for " + type);
            debugOutput.setScanFinisedAt(System.currentTimeMillis());
            debugOutput.setFinalQueueSize(PoolManager.getInstance().getService().getQueue().size());
            if (DebugManager.getInstance().isDebugEnabled()) {
                result.setDebugOutput(debugOutput);
            }

            return result;
        } catch (Throwable T) {

            LOGGER.error("Failed to scan:" + request.getUrl() + " for " + type, T);
            return new ScanResult(type.name(), true, new TranslateableMessage("REPORT_CONSTRUCTION", new ErrorTestInfo(
                    T.getMessage())), 0, new LinkedList<TestResult>());
        } finally {
            Thread.currentThread().setName(Thread.currentThread().getName().replace("-" + request.getUrl(), ""));
        }
    }

    public String scanResultToJson(ScanResult result) {
        ObjectMapper ow = new ObjectMapper();
        String json = "";
        try {
            json = ow.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            LOGGER.error("Could not convert to json", ex);
        }
        return json;
    }

    public String scanResultToJson(CollectedScanResult result) {
        ObjectMapper ow = new ObjectMapper();
        String json = "";
        try {
            json = ow.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            LOGGER.error("Could not convert to json", ex);
        }
        return json;
    }

    public void answer(ScanResult result) {
        String json = scanResultToJson(result);
        for (String callback : request.getCallbackurls()) {
            LOGGER.info("Calling back: " + callback + " for " + result.getName());
            try {
                URL url = new URL(callback);
                URLConnection con = url.openConnection();
                HttpURLConnection http = (HttpURLConnection) con;
                con.setConnectTimeout(10000);
                http.setRequestMethod("POST");
                http.setDoInput(true);
                http.setDoOutput(true);
                http.setFixedLengthStreamingMode(json.getBytes().length);
                http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                http.connect();
                try (OutputStream os = http.getOutputStream()) {
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                }
                LOGGER.debug(json);
                http.disconnect();
            } catch (Exception ex) {
                LOGGER.warn("Failed to callback:" + callback, ex);
            }
        }
    }

    public void answer(CollectedScanResult result) {
        String json = scanResultToJson(result);
        for (String callback : request.getCallbackurls()) {
            LOGGER.info("Calling back: " + callback + " for " + result.getName());
            try {
                URL url = new URL(callback);
                URLConnection con = url.openConnection();
                HttpURLConnection http = (HttpURLConnection) con;
                http.setRequestMethod("POST");
                http.setDoInput(true);
                http.setDoOutput(true);
                http.setFixedLengthStreamingMode(json.getBytes().length);
                http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                http.connect();
                try (OutputStream os = http.getOutputStream()) {
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                }
                LOGGER.debug(json);
                http.disconnect();
            } catch (IOException ex) {
                LOGGER.warn("Failed to callback:" + callback, ex);
            }
        }
    }

    public ScanResult reportToScanResult(SiteReport report, ScanType type) {
        if (!Objects.equals(report.getServerIsAlive(), Boolean.TRUE)) {
            if (type == ScanType.TLS) {
                return new ScanResult(type.name(), true, getPortResponse(report), 0, new LinkedList<TestResult>());
            } else {
                ScanResult result = new ScanResult(type.name(), true, getPortResponse(report), 100,
                        new LinkedList<TestResult>());
                result.setScoreType("hidden");
                return result;
            }
        }
        if (!Objects.equals(report.getSupportsSslTls(), Boolean.TRUE)) {
            return new ScanResult(type.name(), true, getTlsSupported(report), 0, new LinkedList<TestResult>());
        }
        List<TestResult> resultList = new LinkedList<>();
        if (report.getProbeTypeList().contains(ProbeType.CERTIFICATE)) {
            resultList.add(getCertificateExpired(report));
            resultList.add(getCertificateNotValidYet(report));
            resultList.add(getCertificateNotSentByServer(report));
            resultList.add(getCertificateWeakHashFunction(report));
            // resultList.add(getCertificateWeakSignAlgorithm(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.CIPHERSUITE)) {
            resultList.add(getSupportsAnon(report));
            resultList.add(getSupportsExport(report));
            resultList.add(getSupportsNull(report));
            resultList.add(getSupportsRc4(report));
            resultList.add(getSupportsDes(report));
            resultList.add(getSweet32Vulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.CIPHERSUITE_ORDER)) {
            resultList.add(getCipherSuiteOrder(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.PROTOCOL_VERSION)) {
            resultList.add(getSupportsSsl2(report));
            resultList.add(getSupportsSsl3(report));
            resultList.add(getSupportsTls13(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.BLEICHENBACHER)) {
            resultList.add(getBleichenbacherVulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.COMPRESSIONS)) {
            resultList.add(getCrimeVulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.HEARTBLEED)) {
            resultList.add(getHeartbleedVulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.EARLY_CCS)) {
            resultList.add(getEarlyCcsVulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.INVALID_CURVE)) {
            resultList.add(getInvalidCurveEphemeralVulnerable(report));
            resultList.add(getInvalidCurveVulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.PADDING_ORACLE)) {
            resultList.add(getPaddingOracleVulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.POODLE)) {
            resultList.add(getPoodleVulnerable(report));
        }
        if (report.getProbeTypeList().contains(ProbeType.TLS_POODLE)) {
            resultList.add(getTlsPoodleVulnerable(report));
        }
        // if (report.getProbeTypeList().contains(ProbeType.CVE20162107)) {
        // resultList.add(getCve20162107Vulnerable(report));
        // }

        int max = 100;
        boolean hasError = false;
        boolean hasCritical = false;
        boolean hasWarning = false;
        int count = 0;
        int score = 0;
        for (TestResult result : resultList) {
            if (result.getScoreType().equals("hidden")) {
                continue;
            }
            if (result.getScore() < max
                    && (result.getScoreType().equals("fatal") || result.getScoreType().equals("critical"))) {
                max = result.getScore();
                hasCritical = true;
            }

            hasError |= result.isHasError();
            if (!hasError) {
                score += result.getScore();
                count++;
            }
        }

        if (count != 0) {
            score = score / count;
        } else {
            score = 0;
        }
        if (score > max && (hasCritical || hasWarning)) {
            score = (int) (score * (((double) max) / 100));
        }
        // Rewrite critical fatal
        for (TestResult result : resultList) {
            if (result.getScoreType().equals("critical")) {
                result.setScoreType("warning");
            } else if (result.getScoreType().equals("fatal")) {
                result.setScoreType("critical");
            }
        }
        ScanResult result = new ScanResult(type.name(), false, null, score, resultList);
        return result;
    }

    private TranslateableMessage getPortResponse(SiteReport report) {
        return new TranslateableMessage("PORT_NO_RESPONSE", new HostTestInfo(report.getHost()));

    }

    private TranslateableMessage getTlsSupported(SiteReport report) {
        return new TranslateableMessage("TLS_NOT_SUPPORTED", new HostTestInfo(report.getHost()));
    }

    private TestResult getCertificateExpired(SiteReport report) {
        List<TranslateableMessage> messageList = new LinkedList<>();
        Date tempDate = null;
        String certString = null;
        for (CertificateReport certReport : report.getCertificateChain().getCertificateReportList()) {
            if (certReport.getValidTo().before(new Date(System.currentTimeMillis()))) {
                tempDate = certReport.getValidTo();
                certString = certReport.toString();
                break;
            }
        }
        if (tempDate != null) {
            List<TestInfo> pairList = new LinkedList<>();
            pairList.add(new CertificateTestInfo(certString));
            messageList.add(new TranslateableMessage("EXPIRED", new DateTestInfo(DateFormat.getDateInstance().format(
                    tempDate))));
        } else {
            messageList = null;
        }
        boolean hasError = report.getCertificateChain().getContainsExpired() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CERTIFICATE_EXPIRED", hasError, errorMessage, report.getCertificateChain()
                .getContainsExpired() == Boolean.TRUE ? 0 : 100,
                !report.getCertificateChain().getContainsExpired() == Boolean.TRUE ? "success" : "critical",
                messageList);
    }

    private TestResult getCertificateNotValidYet(SiteReport report) {
        List<TranslateableMessage> messageList = new LinkedList<>();
        Date tempDate = null;
        for (CertificateReport certReport : report.getCertificateChain().getCertificateReportList()) {
            if (certReport.getValidFrom().after(new Date(System.currentTimeMillis()))) {
                tempDate = certReport.getValidFrom();
                break;
            }
        }
        if (tempDate != null) {
            messageList.add(new TranslateableMessage("NOT_YET_VALID", new DateTestInfo(DateFormat.getDateInstance()
                    .format(tempDate))));
        } else {
            messageList = null;
        }
        boolean hasError = report.getCertificateChain().getContainsNotYetValid() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CERTIFICATE_NOT_VALID_YET", hasError, errorMessage, report.getCertificateChain()
                .getContainsNotYetValid() ? 10 : 100,
                !report.getCertificateChain().getContainsNotYetValid() == Boolean.TRUE ? "success" : "warning",
                messageList);
    }

    private TestResult getCertificateNotSentByServer(SiteReport report) {
        boolean hasError = report.getCertificate() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }

        if (report.getCertificate() == null) {
            return new TestResult("CERTIFICATE_NOT_SENT_BY_SERVER", hasError, errorMessage, 0, "critical", null);
        }
        return new TestResult("CERTIFICATE_NOT_SENT_BY_SERVER", hasError, errorMessage, report.getCertificate()
                .getLength() > 0 ? 100 : 0, report.getCertificate().getLength() > 0 ? "hidden" : "critical", null);
    }

    private TestResult getCertificateWeakHashFunction(SiteReport report) {
        String hashAlgo = null;
        List<TranslateableMessage> messageList = new LinkedList<>();
        if (report.getCertificateChain() != null) {
            for (CertificateReport certReport : report.getCertificateChain().getCertificateReportList()) {
                if (certReport.getSignatureAndHashAlgorithm().getHashAlgorithm() == HashAlgorithm.MD5
                        || certReport.getSignatureAndHashAlgorithm().getHashAlgorithm() == HashAlgorithm.SHA1) {
                    hashAlgo = certReport.getSignatureAndHashAlgorithm().getHashAlgorithm().name();
                    messageList.add(new TranslateableMessage("HASH_ALGO", new HashTestInfo(hashAlgo)));

                    break;
                }
            }

        }
        boolean critical = false;
        if (hashAlgo != null && hashAlgo.equals(HashAlgorithm.MD5.name())) {
            critical = true;
        }
        if (messageList.isEmpty()) {
            messageList = null;
        }
        boolean hasError = report.getCertificateChain().getContainsWeakSignedNonTruststoresCertificates() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE)
            ;
        {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        if (critical) {
            return new TestResult(
                    "CERTIFICATE_WEAK_HASH_FUNCTION",
                    hasError,
                    errorMessage,
                    report.getCertificateChain().getContainsWeakSignedNonTruststoresCertificates() ? 0 : 100,
                    !report.getCertificateChain().getContainsWeakSignedNonTruststoresCertificates() == Boolean.TRUE ? "success"
                            : "critical", messageList);

        } else {

            return new TestResult("CERTIFICATE_WEAK_HASH_FUNCTION", report.getCertificateChain()
                    .getContainsWeakSignedNonTruststoresCertificates() == null, null, report.getCertificateChain()
                    .getContainsWeakSignedNonTruststoresCertificates() ? 50 : 100, !report.getCertificateChain()
                    .getContainsWeakSignedNonTruststoresCertificates() == Boolean.TRUE ? "success" : "warning",
                    messageList);
        }
    }

    private TestResult getSupportsAnon(SiteReport report) {
        List<TranslateableMessage> messageList = new LinkedList<>();
        List<CipherSuite> suiteList = new LinkedList<>();
        for (CipherSuite suite : report.getCipherSuites()) {
            if (suite.name().contains("anon")) {
                suiteList.add(suite);
            }
        }
        if (suiteList.size() > 0) {
            messageList.add(new TranslateableMessage("ANON_SUITES", convertSuiteList(suiteList)));
        } else {
            messageList = null;
        }
        boolean hasError = report.getSupportsAnonCiphers() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CIPHERSUITE_ANON", hasError, errorMessage, Objects.equals(
                report.getSupportsAnonCiphers(), Boolean.TRUE) ? 0 : 100, !(Objects.equals(
                report.getSupportsAnonCiphers(), Boolean.TRUE)) ? "success" : "fatal", messageList);
    }

    private TestInfo convertSuiteList(List<CipherSuite> suiteList) {
        StringBuilder builder = new StringBuilder();
        for (CipherSuite suite : suiteList) {
            builder.append(suite.name()).append(" ");
        }
        return new CiphersuitesTestInfo(builder.toString());
    }

    private TestResult getSupportsExport(SiteReport report) {
        List<TranslateableMessage> messageList = new LinkedList<>();
        List<CipherSuite> suiteList = new LinkedList<>();
        for (CipherSuite suite : report.getCipherSuites()) {
            if (suite.name().toUpperCase().contains("EXPORT")) {
                suiteList.add(suite);
            }
        }
        if (suiteList.size() > 0) {
            messageList.add(new TranslateableMessage("EXPORT_SUITES", convertSuiteList(suiteList)));
        } else {
            messageList = null;
        }
        boolean hasError = report.getSupportsExportCiphers() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CIPHERSUITE_EXPORT", hasError, errorMessage, Objects.equals(
                report.getSupportsExportCiphers(), Boolean.TRUE) ? 0 : 100, !(Objects.equals(
                report.getSupportsExportCiphers(), Boolean.TRUE)) ? "success" : "fatal", messageList);
    }

    private TestResult getSupportsNull(SiteReport report) {
        List<TranslateableMessage> messageList = new LinkedList<>();
        List<CipherSuite> suiteList = new LinkedList<>();
        for (CipherSuite suite : report.getCipherSuites()) {
            if (suite.name().toUpperCase().contains("NULL")) {
                suiteList.add(suite);
            }
        }
        if (suiteList.size() > 0) {
            messageList.add(new TranslateableMessage("NULL_SUITES", convertSuiteList(suiteList)));
        } else {
            messageList = null;
        }
        boolean hasError = report.getSupportsNullCiphers() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CIPHERSUITE_NULL", hasError, errorMessage,
                report.getSupportsNullCiphers() == Boolean.TRUE ? 0 : 100,
                !(report.getSupportsNullCiphers() == Boolean.TRUE) ? "success" : "fatal", messageList);
    }

    private TestResult getSupportsRc4(SiteReport report) {
        List<TranslateableMessage> messageList = new LinkedList<>();
        List<CipherSuite> suiteList = new LinkedList<>();
        for (CipherSuite suite : report.getCipherSuites()) {
            if (suite.name().toUpperCase().contains("RC4")) {
                suiteList.add(suite);
            }
        }
        if (suiteList.size() > 0) {
            messageList.add(new TranslateableMessage("RC4_SUITES", convertSuiteList(suiteList)));
        } else {
            messageList = null;
        }
        boolean hasError = report.getSupportsRc4Ciphers() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CIPHERSUITE_RC4", hasError, errorMessage,
                report.getSupportsRc4Ciphers() == Boolean.TRUE ? 30 : 100,
                !(report.getSupportsRc4Ciphers() == Boolean.TRUE) ? "success" : "warning", messageList);
    }

    private TestResult getCipherSuiteOrder(SiteReport report) {
        boolean hasError = report.getEnforcesCipherSuiteOrdering() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CIPHERSUITEORDER_ENFORCED", hasError, errorMessage,
                report.getEnforcesCipherSuiteOrdering() == Boolean.TRUE ? 100 : 90,
                (report.getEnforcesCipherSuiteOrdering() == Boolean.TRUE) ? "success" : "warning", null);
    }

    private TestResult getSupportsSsl2(SiteReport report) {
        boolean hasError = report.getSupportsSsl2() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("PROTOCOLVERSION_SSL2", hasError, errorMessage,
                report.getSupportsSsl2() == Boolean.TRUE ? 0 : 100,
                !(report.getSupportsSsl3() == Boolean.TRUE) ? "success" : "fatal", null);
    }

    private TestResult getSupportsSsl3(SiteReport report) {
        boolean hasError = report.getSupportsSsl3() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("PROTOCOLVERSION_SSL3", hasError, errorMessage,
                report.getSupportsSsl3() == Boolean.TRUE ? 0 : 100,
                !(report.getSupportsSsl3() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getBleichenbacherVulnerable(SiteReport report) {
        boolean hasError = report.getBleichenbacherVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("BLEICHENBACHER_VULNERABLE", hasError, errorMessage,
                report.getBleichenbacherVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getBleichenbacherVulnerable() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getPaddingOracleVulnerable(SiteReport report) {
        boolean hasError = report.getPaddingOracleVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("PADDING_ORACLE_VULNERABLE", hasError, errorMessage,
                report.getPaddingOracleVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getPaddingOracleVulnerable() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getInvalidCurveVulnerable(SiteReport report) {
        boolean hasError = report.getInvalidCurveVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("INVALID_CURVE_VULNERABLE", hasError, errorMessage,
                report.getInvalidCurveVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getInvalidCurveVulnerable() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getCve20162107Vulnerable(SiteReport report) {
        boolean hasError = report.getCve20162107Vulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CVE20162107_VULNERABLE", hasError, errorMessage,
                report.getCve20162107Vulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getCve20162107Vulnerable() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getInvalidCurveEphemeralVulnerable(SiteReport report) {
        boolean hasError = report.getInvalidCurveEphermaralVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("INVALID_CURVE_EPHEMERAL_VULNERABLE", hasError, errorMessage,
                report.getInvalidCurveEphermaralVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getInvalidCurveEphermaralVulnerable() == Boolean.TRUE) ? "success" : "warning", null);
    }

    private TestResult getPoodleVulnerable(SiteReport report) {
        boolean hasError = report.getPoodleVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("POODLE_VULNERABLE", hasError, errorMessage,
                report.getPoodleVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getPoodleVulnerable() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getTlsPoodleVulnerable(SiteReport report) {
        boolean hasError = report.getTlsPoodleVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("TLS_POODLE_VULNERABLE", hasError, errorMessage,
                report.getTlsPoodleVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getTlsPoodleVulnerable() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getCrimeVulnerable(SiteReport report) {
        boolean hasError = report.getCrimeVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CRIME_VULNERABLE", hasError, errorMessage,
                report.getCrimeVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getCrimeVulnerable() == Boolean.TRUE) ? "success" : "critical", null);
    }

    private TestResult getSweet32Vulnerable(SiteReport report) {
        boolean hasError = report.getSweet32Vulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("SWEET32_VULNERABLE", hasError, errorMessage,
                report.getSweet32Vulnerable() == Boolean.TRUE ? 80 : 100,
                !(report.getSweet32Vulnerable() == Boolean.TRUE) ? "success" : "warning", null);
    }

    private TestResult getHeartbleedVulnerable(SiteReport report) {
        boolean hasError = report.getHeartbleedVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("HEARTBLEED_VULNERABLE", hasError, errorMessage,
                report.getHeartbleedVulnerable() == Boolean.TRUE ? 0 : 100,
                !(report.getHeartbleedVulnerable() == Boolean.TRUE) ? "success" : "fatal", null);
    }

    private TestResult getSupportsTls13(SiteReport report) {
        boolean hasError = report.supportsAnyTls13() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("PROTOCOLVERSION_TLS13", hasError, errorMessage,
                report.supportsAnyTls13() == Boolean.TRUE ? 100 : 0,
                report.supportsAnyTls13() == Boolean.TRUE ? "bonus" : "hidden", null);
    }

    private TestResult getSupportsDes(SiteReport report) {
        List<CipherSuite> suiteList = new LinkedList<>();
        for (CipherSuite suite : report.getCipherSuites()) {
            if (suite.name().toUpperCase().contains("_DES")) {
                suiteList.add(suite);
            }
        }
        List<TranslateableMessage> messageList = new LinkedList<>();
        if (suiteList.size() > 0) {
            messageList.add(new TranslateableMessage("DES_SUITES", convertSuiteList(suiteList)));
        } else {
            messageList = null;
        }

        boolean hasError = report.getSupportsDesCiphers() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }
        return new TestResult("CIPHERSUITE_DES", hasError, errorMessage,
                report.getSupportsDesCiphers() == Boolean.TRUE ? 0 : 100,
                !(report.getSupportsDesCiphers() == Boolean.TRUE) ? "success" : "warning", messageList);
    }

    private TestResult getEarlyCcsVulnerable(SiteReport report) {
        boolean hasError = report.getEarlyCcsVulnerable() == null;
        TranslateableMessage errorMessage = null;
        if (hasError == Boolean.TRUE) {
            errorMessage = new TranslateableMessage("ERROR_GENERIC", null);
        }

        return new TestResult(
                "EARLYCCS_VULNERABLE",
                hasError,
                errorMessage,
                report.getEarlyCcsVulnerable() == EarlyCcsVulnerabilityType.VULN_EXPLOITABLE ? 0 : 100,
                !(report.getEarlyCcsVulnerable() == EarlyCcsVulnerabilityType.VULN_EXPLOITABLE) ? "success" : "warning",
                null);
    }
}
