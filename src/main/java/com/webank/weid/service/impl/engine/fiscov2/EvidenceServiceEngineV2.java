

package com.webank.weid.service.impl.engine.fiscov2;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.fisco.bcos.sdk.abi.datatypes.generated.Bytes32;
import org.fisco.bcos.sdk.abi.datatypes.generated.Uint256;
import org.fisco.bcos.sdk.abi.datatypes.generated.tuples.generated.Tuple4;
import org.fisco.bcos.sdk.abi.datatypes.generated.tuples.generated.Tuple5;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.client.protocol.response.BcosTransactionReceiptsDecoder;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.model.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webank.weid.constant.CnsType;
import com.webank.weid.constant.ErrorCode;
import com.webank.weid.constant.ResolveEventLogStatus;
import com.webank.weid.constant.WeIdConstant;
import com.webank.weid.contract.v2.EvidenceContract;
import com.webank.weid.contract.v2.EvidenceContract.EvidenceAttributeChangedEventResponse;
import com.webank.weid.contract.v2.EvidenceContract.EvidenceExtraAttributeChangedEventResponse;
import com.webank.weid.exception.WeIdBaseException;
import com.webank.weid.protocol.base.EvidenceInfo;
import com.webank.weid.protocol.base.EvidenceSignInfo;
import com.webank.weid.protocol.response.ResolveEventLogResult;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.protocol.response.TransactionInfo;
import com.webank.weid.service.impl.engine.BaseEngine;
import com.webank.weid.service.impl.engine.EvidenceServiceEngine;
import com.webank.weid.suite.cache.CacheManager;
import com.webank.weid.suite.cache.CacheNode;
import com.webank.weid.util.DataToolUtils;
import com.webank.weid.util.WeIdUtils;

/**
 * EvidenceServiceEngine calls evidence contract which runs on FISCO BCOS 2.0.
 *
 * @author yanggang, chaoxinhu
 */
public class EvidenceServiceEngineV2 extends BaseEngine implements EvidenceServiceEngine {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceServiceEngineV2.class);

    private static CacheNode<BcosTransactionReceiptsDecoder> receiptsNode =
        CacheManager.registerCacheNode("SYS_TX_RECEIPTS", 1000 * 3600 * 24L);

    private EvidenceContract evidenceContract;

    private String evidenceAddress;

    private String groupId;

    /**
     * 构造函数.
     *
     * @param groupId 群组编号
     */
    public EvidenceServiceEngineV2(String groupId) {
        super(groupId);
        this.groupId = groupId;
        initEvidenceAddress();
        evidenceContract = getContractService(this.evidenceAddress, EvidenceContract.class);
    }

    private void initEvidenceAddress() {
        if (groupId == null || masterGroupId.equals(groupId)) {
            logger.info("[initEvidenceAddress] the groupId is master.");
            this.evidenceAddress = fiscoConfig.getEvidenceAddress();
            return;
        }
        this.evidenceAddress = super.getBucket(CnsType.ORG_CONFING).get(
            fiscoConfig.getCurrentOrgId(), WeIdConstant.CNS_EVIDENCE_ADDRESS + groupId).getResult();
        if (StringUtils.isBlank(evidenceAddress)) {
            throw new WeIdBaseException("can not found the evidence address from chain, you may "
                + "not activate the evidence contract on WeID Build Tools.");
        }
        logger.info(
            "[initEvidenceAddress] get the address from cns. address = {}",
            evidenceAddress
        );
    }

    @Override
    public ResponseData<String> createEvidence(
        String hashValue,
        String signature,
        String extra,
        Long timestamp,
        String privateKey
    ) {
        try {
            List<byte[]> hashByteList = new ArrayList<>();
            if (!DataToolUtils.isValidHash(hashValue)) {
                return new ResponseData<>(StringUtils.EMPTY, ErrorCode.ILLEGAL_INPUT, null);
            }
            hashByteList.add(DataToolUtils.convertHashStrIntoHashByte32Array(hashValue));
            String address = DataToolUtils.addressFromPrivate(new BigInteger(privateKey));
            List<String> signerList = new ArrayList<>();
            signerList.add(address);
            List<String> sigList = new ArrayList<>();
            sigList.add(signature);
            List<String> logList = new ArrayList<>();
            logList.add(extra);
            List<BigInteger> timestampList = new ArrayList<>();
            timestampList.add(new BigInteger(String.valueOf(timestamp), 10));
            EvidenceContract evidenceContractWriter =
                reloadContract(
                    this.evidenceAddress,
                    privateKey,
                    EvidenceContract.class
                );
            TransactionReceipt receipt =
                evidenceContractWriter.createEvidence(
                    hashByteList,
                    signerList,
                    sigList,
                    logList,
                    timestampList
                );

            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceContract.CreateEvidenceEventResponse> eventList =
                evidenceContract.getCreateEvidenceEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(StringUtils.EMPTY,
                    ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(StringUtils.EMPTY,
                    ErrorCode.CREDENTIAL_EVIDENCE_ALREADY_EXISTS, info);
            } else {
                for (EvidenceContract.CreateEvidenceEventResponse event : eventList) {
                    if (event.sig.equalsIgnoreCase(signature)
                        && event.signer.equalsIgnoreCase(address)) {
                        return new ResponseData<>(hashValue, ErrorCode.SUCCESS, info);
                    }
                }
            }
            return new ResponseData<>(StringUtils.EMPTY,
                ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT);
        } catch (Exception e) {
            logger.error("create evidence failed due to system error. ", e);
            return new ResponseData<>(StringUtils.EMPTY, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    @Override
    public ResponseData<List<Boolean>> batchCreateEvidence(
        List<String> hashValues,
        List<String> signatures,
        List<String> logs,
        List<Long> timestamp,
        List<String> signers,
        String privateKey
    ) {
        List<Boolean> result = new ArrayList<>();
        for (int i = 0; i < hashValues.size(); i++) {
            result.add(false);
        }
        try {
            List<byte[]> hashByteList = new ArrayList<>();
            List<String> signerList = new ArrayList<>();
            List<BigInteger> timestampList = new ArrayList<>();
            List<String> logList = new ArrayList<>();
            List<String> sigList = new ArrayList<>();
            for (int i = 0; i < hashValues.size(); i++) {
                if (hashValues.get(i) == null) {
                    hashValues.set(i, StringUtils.EMPTY);
                }
                if (!DataToolUtils.isValidHash(hashValues.get(i))) {
                    continue;
                }
                hashByteList
                    .add(DataToolUtils.convertHashStrIntoHashByte32Array(hashValues.get(i)));
                signerList.add(WeIdUtils.convertWeIdToAddress(signers.get(i)));
                timestampList.add(new BigInteger(String.valueOf(timestamp.get(i)), 10));
                logList.add(logs.get(i));
                sigList.add(signatures.get(i));
            }
            EvidenceContract evidenceContractWriter =
                reloadContract(
                    this.evidenceAddress,
                    privateKey,
                    EvidenceContract.class
                );
            TransactionReceipt receipt =
                evidenceContractWriter.createEvidence(
                    hashByteList,
                    signerList,
                    sigList,
                    logList,
                    timestampList
                );

            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceContract.CreateEvidenceEventResponse> eventList =
                evidenceContractWriter.getCreateEvidenceEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(result, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(result, ErrorCode.CREDENTIAL_EVIDENCE_ALREADY_EXISTS,
                    info);
            } else {
                List<String> returnedHashs = new ArrayList<>();
                for (EvidenceContract.CreateEvidenceEventResponse event : eventList) {
                    //Object[] hashArray = event.hash.toArray();
                    returnedHashs.add(DataToolUtils.convertHashByte32ArrayIntoHashStr(
                            (new Bytes32(event.hash)).getValue()));
                }
                return new ResponseData<>(
                    DataToolUtils.strictCheckExistence(hashValues, returnedHashs),
                    ErrorCode.SUCCESS, info);
            }
        } catch (Exception e) {
            logger.error("create evidence failed due to system error. ", e);
            return new ResponseData<>(result, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    @Override
    public ResponseData<List<Boolean>> batchCreateEvidenceWithCustomKey(
        List<String> hashValues,
        List<String> signatures,
        List<String> logs,
        List<Long> timestamp,
        List<String> signers,
        List<String> customKeys,
        String privateKey
    ) {
        List<Boolean> result = new ArrayList<>();
        for (int i = 0; i < hashValues.size(); i++) {
            result.add(false);
        }
        try {
            List<byte[]> hashByteList = new ArrayList<>();
            List<String> signerList = new ArrayList<>();
            List<BigInteger> timestampList = new ArrayList<>();
            List<String> customKeyList = new ArrayList<>();
            List<String> logList = new ArrayList<>();
            List<String> sigList = new ArrayList<>();
            for (int i = 0; i < hashValues.size(); i++) {
                if (hashValues.get(i) == null) {
                    hashValues.set(i, StringUtils.EMPTY);
                }
                if (!DataToolUtils.isValidHash(hashValues.get(i))) {
                    continue;
                }
                hashByteList
                    .add(DataToolUtils.convertHashStrIntoHashByte32Array(hashValues.get(i)));
                signerList.add(WeIdUtils.convertWeIdToAddress(signers.get(i)));
                timestampList.add(new BigInteger(String.valueOf(timestamp.get(i)), 10));
                customKeyList.add(customKeys.get(i));
                logList.add(logs.get(i));
                sigList.add(signatures.get(i));
            }
            EvidenceContract evidenceContractWriter =
                reloadContract(
                    this.evidenceAddress,
                    privateKey,
                    EvidenceContract.class
                );
            TransactionReceipt receipt =
                evidenceContractWriter.createEvidenceWithExtraKey(
                    hashByteList,
                    signerList,
                    sigList,
                    logList,
                    timestampList,
                    customKeyList
                );

            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceContract.CreateEvidenceEventResponse> eventList =
                evidenceContractWriter.getCreateEvidenceEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(result,
                    ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(result, ErrorCode.CREDENTIAL_EVIDENCE_ALREADY_EXISTS,
                    info);
            } else {
                List<String> returnedHashs = new ArrayList<>();
                for (EvidenceContract.CreateEvidenceEventResponse event : eventList) {
                    //Object[] hashArray = event.hash.toArray();
                    returnedHashs.add(DataToolUtils.convertHashByte32ArrayIntoHashStr(
                            (new Bytes32(event.hash)).getValue()));
                }
                return new ResponseData<>(
                    DataToolUtils.strictCheckExistence(hashValues, returnedHashs),
                    ErrorCode.SUCCESS, info);
            }
        } catch (Exception e) {
            logger.error("create evidence failed due to system error. ", e);
            return new ResponseData<>(result, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    @Override
    public ResponseData<Boolean> addLog(
        String hashValue,
        String sig,
        String log,
        Long timestamp,
        String privateKey
    ) {
        try {
            List<byte[]> hashByteList = new ArrayList<>();
            if (!DataToolUtils.isValidHash(hashValue)) {
                return new ResponseData<>(false, ErrorCode.ILLEGAL_INPUT, null);
            }
            hashByteList.add(DataToolUtils.convertHashStrIntoHashByte32Array(hashValue));
            List<String> sigList = new ArrayList<>();
            sigList.add(sig);
            List<String> logList = new ArrayList<>();
            logList.add(log);
            List<BigInteger> timestampList = new ArrayList<>();
            timestampList.add(new BigInteger(String.valueOf(timestamp), 10));
            String address = DataToolUtils.addressFromPrivate(new BigInteger(privateKey));
            List<String> signerList = new ArrayList<>();
            signerList.add(address);
            EvidenceContract evidenceContractWriter =
                reloadContract(
                    this.evidenceAddress,
                    privateKey,
                    EvidenceContract.class
                );
            TransactionReceipt receipt =
                evidenceContractWriter.addSignatureAndLogs(
                    hashByteList,
                    signerList,
                    sigList,
                    logList,
                    timestampList
                );
            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceAttributeChangedEventResponse> eventList =
                evidenceContractWriter.getEvidenceAttributeChangedEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_NOT_EXIST, info);
            } else {
                for (EvidenceAttributeChangedEventResponse event : eventList) {
                    if (event.signer.equalsIgnoreCase(address)) {
                        return new ResponseData<>(true, ErrorCode.SUCCESS, info);
                    }
                }
            }
            return new ResponseData<>(false,
                ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT);
        } catch (Exception e) {
            logger.error("add log failed due to system error. ", e);
            return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    @Override
    public ResponseData<Boolean> addLogByCustomKey(
        String hashValue,
        String sig,
        String log,
        Long timestamp,
        String customKey,
        String privateKey
    ) {
        try {
            List<byte[]> hashByteList = new ArrayList<>();
            if (!DataToolUtils.isValidHash(hashValue)) {
                return new ResponseData<>(false, ErrorCode.ILLEGAL_INPUT, null);
            }
            hashByteList.add(DataToolUtils.convertHashStrIntoHashByte32Array(hashValue));
            List<String> sigList = new ArrayList<>();
            sigList.add(sig);
            List<String> logList = new ArrayList<>();
            logList.add(log);
            List<BigInteger> timestampList = new ArrayList<>();
            timestampList.add(new BigInteger(String.valueOf(timestamp), 10));
            /*String address = WeIdUtils
                .convertWeIdToAddress(DataToolUtils.convertPrivateKeyToDefaultWeId(privateKey));*/

            String address = DataToolUtils.addressFromPrivate(new BigInteger(privateKey));
            List<String> signerList = new ArrayList<>();
            signerList.add(address);
            List<String> customKeyList = new ArrayList<>();
            customKeyList.add(customKey);
            EvidenceContract evidenceContractWriter =
                reloadContract(
                    this.evidenceAddress,
                    privateKey,
                    EvidenceContract.class
                );
            signerList.add(address);
            TransactionReceipt receipt =
                evidenceContractWriter.addSignatureAndLogsWithExtraKey(
                    hashByteList,
                    signerList,
                    sigList,
                    logList,
                    timestampList,
                    customKeyList
                );
            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceAttributeChangedEventResponse> eventList =
                evidenceContractWriter.getEvidenceAttributeChangedEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_NOT_EXIST, info);
            } else {
                for (EvidenceAttributeChangedEventResponse event : eventList) {
                    if (event.signer.equalsIgnoreCase(address)) {
                        return new ResponseData<>(true, ErrorCode.SUCCESS, info);
                    }
                }
            }
            return new ResponseData<>(false,
                ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT);
        } catch (Exception e) {
            logger.error("add log failed due to system error. ", e);
            return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    @Override
    public ResponseData<String> getHashByCustomKey(String customKey) {
        try {
            String hash = DataToolUtils.convertHashByte32ArrayIntoHashStr(
                evidenceContract.getHashByExtraKey(customKey));
            if (!StringUtils.isEmpty(hash)) {
                return new ResponseData<>(hash, ErrorCode.SUCCESS);
            }
        } catch (Exception e) {
            logger.error("get hash failed.", e);
        }
        return new ResponseData<>(StringUtils.EMPTY, ErrorCode.CREDENTIAL_EVIDENCE_NOT_EXIST);
    }

    /**
     * Get an evidence full info.
     *
     * @param hash evidence hash
     * @return evidence info
     */
    @Override
    public ResponseData<EvidenceInfo> getInfo(String hash) {
        EvidenceInfo evidenceInfo = new EvidenceInfo();
        evidenceInfo.setCredentialHash(hash);
        byte[] hashByte = DataToolUtils.convertHashStrIntoHashByte32Array(hash);
        try {
            Tuple5<List<String>, List<String>, List<String>, List<BigInteger>, List<Boolean>> result = evidenceContract.getEvidence(hashByte);
            if (result == null) {
                return new ResponseData<>(null, ErrorCode.CREDENTIAL_EVIDENCE_NOT_EXIST);
            }
            Map<String, EvidenceSignInfo> signInfoMap = new HashMap<>();
            for(int i=0; i<result.getValue1().size(); i++){
                //如果signer已经存在（通过addSignatureAndLogs添加的签名和log），则覆盖签名，把log添加到已有的logs列表
                if(signInfoMap.containsKey(result.getValue1().get(i))){
                    if(!StringUtils.isEmpty(result.getValue2().get(i))){
                        signInfoMap.get(result.getValue1().get(i)).setSignature(result.getValue2().get(i));
                    }
                    if(!StringUtils.isEmpty(result.getValue3().get(i))){
                        signInfoMap.get(result.getValue1().get(i)).getLogs().add(result.getValue3().get(i));
                    }
                    signInfoMap.get(result.getValue1().get(i)).setTimestamp(result.getValue4().get(i).toString());
                }else{
                    EvidenceSignInfo evidenceSignInfo = new EvidenceSignInfo();
                    evidenceSignInfo.setSignature(result.getValue2().get(i));
                    if(!StringUtils.isEmpty(result.getValue3().get(i))){
                        evidenceSignInfo.getLogs().add(result.getValue3().get(i));
                    }
                    evidenceSignInfo.setTimestamp(result.getValue4().get(i).toString());
                    evidenceSignInfo.setRevoked(result.getValue5().get(i));
                    signInfoMap.put(result.getValue1().get(i), evidenceSignInfo);
                }
            }
            evidenceInfo.setSignInfo(signInfoMap);
            // Reverse the order of the list
            /*for (String signer : evidenceInfo.getSigners()) {
                List<String> extraList = evidenceInfo.getSignInfo().get(signer).getLogs();
                if (extraList != null && !extraList.isEmpty()) {
                    Collections.reverse(evidenceInfo.getSignInfo().get(signer).getLogs());
                }
            }*/
            return new ResponseData<>(evidenceInfo, ErrorCode.SUCCESS);
        } catch (Exception e) {
            logger.error("get evidence failed.", e);
            return new ResponseData<>(null, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    private void resolveTransaction(
        String hash,
        int startBlockNumber,
        EvidenceInfo evidenceInfo,
        Map<String, List<String>> perSignerRedoLog) {

        int previousBlock = startBlockNumber;
        while (previousBlock != 0) {
            int currentBlockNumber = previousBlock;
            BcosTransactionReceiptsDecoder bcosTransactionReceiptsDecoder = null;
            try {
                bcosTransactionReceiptsDecoder = receiptsNode.get(String.valueOf(currentBlockNumber));
                if (bcosTransactionReceiptsDecoder == null) {
                    bcosTransactionReceiptsDecoder = ((Client) weServer.getWeb3j())
                        .getBatchReceiptsByBlockNumberAndRange(BigInteger.valueOf(currentBlockNumber), "0", "-1");
                    // Store big transactions into memory (bigger than 1) to avoid memory explode
                    if (bcosTransactionReceiptsDecoder != null
                        && bcosTransactionReceiptsDecoder.decodeTransactionReceiptsInfo()
                        .getTransactionReceipts().size() > WeIdConstant.RECEIPTS_COUNT_THRESHOLD) {
                        receiptsNode
                            .put(String.valueOf(currentBlockNumber), bcosTransactionReceiptsDecoder);
                    }
                }
            } catch (Exception e) {
                logger.error(
                    "Get block by number:{} failed. Exception message:{}", currentBlockNumber, e);
            }
            if (bcosTransactionReceiptsDecoder == null) {
                logger.info("Get block by number:{}. latestBlock is null", currentBlockNumber);
                return;
            }
            previousBlock = 0;
            try {
                List<TransactionReceipt> receipts = bcosTransactionReceiptsDecoder
                    .decodeTransactionReceiptsInfo().getTransactionReceipts();
                for (TransactionReceipt receipt : receipts) {
                    List<TransactionReceipt.Logs> logs = receipt.getLogs();
                    // A same topic will be calculated only once
                    Set<String> topicSet = new HashSet<>();
                    for (TransactionReceipt.Logs log : logs) {
                        if (topicSet.contains(log.getTopics().get(0))) {
                            continue;
                        } else {
                            topicSet.add(log.getTopics().get(0));
                        }
                        ResolveEventLogResult returnValue =
                            resolveEventLog(hash, log, receipt, evidenceInfo, perSignerRedoLog);
                        if (returnValue.getResultStatus().equals(
                            ResolveEventLogStatus.STATUS_SUCCESS)) {
                            if (returnValue.getPreviousBlock() == currentBlockNumber) {
                                continue;
                            }
                            previousBlock = returnValue.getPreviousBlock();
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Get TransactionReceipt by key :{} failed.", hash, e);
            }
        }
    }

    private ResolveEventLogResult resolveEventLog(
        String hash,
        TransactionReceipt.Logs log,
        TransactionReceipt receipt,
        EvidenceInfo evidenceInfo,
        Map<String, List<String>> perSignerRedoLog) {
        String topic = log.getTopics().get(0);
        if (!StringUtils.isBlank(topic)) {
            return resolveAttributeEvent(hash, receipt, evidenceInfo, perSignerRedoLog);
        }
        ResolveEventLogResult response = new ResolveEventLogResult();
        response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_EVENT_NULL);
        return response;
    }

    private ResolveEventLogResult resolveAttributeEvent(
        String hash,
        TransactionReceipt receipt,
        EvidenceInfo evidenceInfo,
        Map<String, List<String>> perSignerRedoLog) {
        List<EvidenceAttributeChangedEventResponse> eventList =
            evidenceContract.getEvidenceAttributeChangedEvents(receipt);
        List<EvidenceExtraAttributeChangedEventResponse> extraEventList =
            evidenceContract.getEvidenceExtraAttributeChangedEvents(receipt);
        ResolveEventLogResult response = new ResolveEventLogResult();
        if (CollectionUtils.isEmpty(eventList) && CollectionUtils.isEmpty(extraEventList)) {
            response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_EVENTLOG_NULL);
            return response;
        }

        int previousBlock = 0;
        if (!CollectionUtils.isEmpty(eventList)) {
            // Actual construction code
            // there should be only 1 attrib-change event so it is fine to do so
            for (EvidenceAttributeChangedEventResponse event : eventList) {
                if (StringUtils.isEmpty(event.signer) || CollectionUtils.sizeIsEmpty(event.hash)) {
                    response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_RES_NULL);
                    return response;
                }
                // the event is a full list of everything. Go thru the list and locate the hash
                if (hash.equalsIgnoreCase(DataToolUtils.convertHashByte32ArrayIntoHashStr(
                        ((new Bytes32(event.hash)).getValue())))) {
                    String signerWeId = WeIdUtils
                            .convertAddressToWeId(event.signer);
                    if (CollectionUtils.size(perSignerRedoLog.get(signerWeId)) == 0) {
                        perSignerRedoLog.put(signerWeId, new ArrayList<>());
                    }
                    String currentLog = event.logs;
                    String currentSig = event.sig;
                    if (!StringUtils.isEmpty(currentLog) && !StringUtils.isEmpty(currentSig)) {
                        // this is a sign/log event, sig will override unconditionally,
                        // but logs will try to append.
                        EvidenceSignInfo signInfo = new EvidenceSignInfo();
                        signInfo.setSignature(currentSig);
                        if (evidenceInfo.getSignInfo().containsKey(signerWeId)) {
                            signInfo.setTimestamp(
                                    evidenceInfo.getSignInfo().get(signerWeId).getTimestamp());
                            List<String> oldLogs = evidenceInfo.getSignInfo().get(signerWeId)
                                    .getLogs();
                            perSignerRedoLog.get(signerWeId).add(currentLog);
                            oldLogs.addAll(perSignerRedoLog.get(signerWeId));
                            perSignerRedoLog.put(signerWeId, new ArrayList<>());
                            signInfo.setLogs(oldLogs);
                            signInfo.setRevoked(
                                    evidenceInfo.getSignInfo().get(signerWeId).getRevoked());
                        } else {
                            signInfo
                                    .setTimestamp(String.valueOf(
                                            (new Uint256(event.updated)).getValue()
                                                    .longValue()));
                            perSignerRedoLog.get(signerWeId).add(currentLog);
                            signInfo.getLogs().addAll(perSignerRedoLog.get(signerWeId));
                            perSignerRedoLog.put(signerWeId, new ArrayList<>());
                        }
                        evidenceInfo.getSignInfo().put(signerWeId, signInfo);
                    } else if (!StringUtils.isEmpty(currentLog)) {
                        // this is a pure log event, just keep appending
                        EvidenceSignInfo tempInfo = new EvidenceSignInfo();
                        if (evidenceInfo.getSignInfo().containsKey(signerWeId)) {
                            // already existing evidenceInfo, hence just append a log entry.
                            // sig will override, timestamp will use existing one (always newer)
                            tempInfo.setSignature(
                                    evidenceInfo.getSignInfo().get(signerWeId).getSignature());
                            tempInfo.setTimestamp(
                                    evidenceInfo.getSignInfo().get(signerWeId).getTimestamp());
                            List<String> oldLogs = evidenceInfo.getSignInfo().get(signerWeId)
                                    .getLogs();
                            //oldLogs.add(currentLog);
                            tempInfo.setLogs(oldLogs);
                            tempInfo.setRevoked(
                                    evidenceInfo.getSignInfo().get(signerWeId).getRevoked());
                            perSignerRedoLog.get(signerWeId).add(currentLog);
                        } else {
                            // haven't constructed anything yet, so create a new one now
                            tempInfo.setSignature(StringUtils.EMPTY);
                            tempInfo
                                    .setTimestamp(String.valueOf(
                                            (new Uint256(event.updated)).getValue()
                                                    .longValue()));
                            //tempInfo.getLogs().add(currentLog);
                            perSignerRedoLog.get(signerWeId).add(currentLog);
                        }
                        evidenceInfo.getSignInfo().put(signerWeId, tempInfo);
                    } else if (!StringUtils.isEmpty(currentSig)) {
                        // this is a pure sig event, just override
                        EvidenceSignInfo signInfo = new EvidenceSignInfo();
                        signInfo.setSignature(currentSig);
                        if (evidenceInfo.getSignInfo().containsKey(signerWeId)) {
                            signInfo.setTimestamp(
                                    evidenceInfo.getSignInfo().get(signerWeId).getTimestamp());
                            evidenceInfo.getSignInfo().get(signerWeId).getLogs()
                                    .addAll(perSignerRedoLog.get(signerWeId));
                            signInfo
                                    .setLogs(evidenceInfo.getSignInfo().get(signerWeId).getLogs());
                            perSignerRedoLog.put(signerWeId, new ArrayList<>());
                            signInfo.setRevoked(
                                    evidenceInfo.getSignInfo().get(signerWeId).getRevoked());
                        } else {
                            signInfo
                                    .setTimestamp(String.valueOf(
                                            (new Uint256(event.updated)).getValue()
                                                    .longValue()));
                            signInfo.setLogs(perSignerRedoLog.get(signerWeId));
                            perSignerRedoLog.put(signerWeId, new ArrayList<>());
                        }
                        evidenceInfo.getSignInfo().put(signerWeId, signInfo);
                    } else {
                        // An empty event
                        continue;
                    }
                    /*previousBlock = ((Uint256) event.previousBlock.toArray()[index]).getValue()
                            .intValue();*/
                }
            }
        }
        if (!CollectionUtils.isEmpty(extraEventList)) {
            for (EvidenceExtraAttributeChangedEventResponse event : extraEventList) {
                if (StringUtils.isEmpty(event.signer) || CollectionUtils.sizeIsEmpty(event.hash)) {
                    response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_RES_NULL);
                    return response;
                }
                for (int index = 0; index < CollectionUtils.size(event.hash); index++) {
                    if (hash.equalsIgnoreCase(DataToolUtils.convertHashByte32ArrayIntoHashStr(
                        ((new Bytes32(event.hash)).getValue())))) {
                        String signerWeId = WeIdUtils
                            .convertAddressToWeId(event.signer);
                        String attributeKey = event.key;
                        if (attributeKey.equalsIgnoreCase(WeIdConstant.EVIDENCE_REVOKE_KEY)) {
                            // this is a revoke event. Use the first to be found unconditionally.
                            if (evidenceInfo.getSignInfo().containsKey(signerWeId)) {
                                // already existing evidence info, just modify status
                                if (evidenceInfo.getSignInfo().get(signerWeId).getRevoked()
                                    == null) {
                                    evidenceInfo.getSignInfo().get(signerWeId).setRevoked(true);
                                }
                            } else {
                                // Non existent evidence info, create a new one with time & stat
                                EvidenceSignInfo signInfo = new EvidenceSignInfo();
                                signInfo.setTimestamp(
                                    String.valueOf((new Uint256(event.updated))
                                        .getValue().longValue()));
                                signInfo.setRevoked(true);
                                evidenceInfo.getSignInfo().put(signerWeId, signInfo);
                            }
                        } else if (attributeKey
                            .equalsIgnoreCase(WeIdConstant.EVIDENCE_UNREVOKE_KEY)) {
                            if (evidenceInfo.getSignInfo().containsKey(signerWeId)) {
                                // already existing evidence info, just modify timestamp & status
                                evidenceInfo.getSignInfo().get(signerWeId).setTimestamp(
                                    String.valueOf((new Uint256(event.updated))
                                        .getValue().longValue()));
                                if (evidenceInfo.getSignInfo().get(signerWeId).getRevoked()
                                    == null) {
                                    evidenceInfo.getSignInfo().get(signerWeId).setRevoked(false);
                                }
                            } else {
                                // Non existent evidence info, create a new one with time & stat
                                EvidenceSignInfo signInfo = new EvidenceSignInfo();
                                signInfo.setTimestamp(
                                    String.valueOf((new Uint256(event.updated))
                                        .getValue().longValue()));
                                signInfo.setRevoked(false);
                                evidenceInfo.getSignInfo().put(signerWeId, signInfo);
                            }
                        } else {
                            // other keys attribute, please add in here
                            continue;
                        }
                        /*previousBlock = ((Uint256) event.previousBlock.toArray()[index]).getValue()
                            .intValue();*/
                    }
                }
            }
        }

        response.setPreviousBlock(previousBlock);
        response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_SUCCESS);
        return response;
    }

    /* (non-Javadoc)
     * @see com.webank.weid.service.impl.engine.EvidenceServiceEngine#createEvidence(
     * java.lang.String, java.lang.String, java.lang.String, java.lang.Long, java.lang.String,
     * java.lang.String)
     */
    @Override
    public ResponseData<String> createEvidenceWithCustomKey(
        String hashValue,
        String signature,
        String extra,
        Long timestamp,
        String extraKey,
        String privateKey) {
        try {
            List<byte[]> hashByteList = new ArrayList<>();
            if (!DataToolUtils.isValidHash(hashValue)) {
                return new ResponseData<>(StringUtils.EMPTY, ErrorCode.ILLEGAL_INPUT, null);
            }
            hashByteList.add(DataToolUtils.convertHashStrIntoHashByte32Array(hashValue));
            /*String address = WeIdUtils
                .convertWeIdToAddress(DataToolUtils.convertPrivateKeyToDefaultWeId(privateKey));*/

            String address = DataToolUtils.addressFromPrivate(new BigInteger(privateKey));
            List<String> signerList = new ArrayList<>();
            signerList.add(address);
            List<String> sigList = new ArrayList<>();
            sigList.add(signature);
            List<String> logList = new ArrayList<>();
            logList.add(extra);
            List<BigInteger> timestampList = new ArrayList<>();
            timestampList.add(new BigInteger(String.valueOf(timestamp), 10));
            List<String> extraKeyList = new ArrayList<>();
            extraKeyList.add(extraKey);
            EvidenceContract evidenceContractWriter =
                reloadContract(
                    this.evidenceAddress,
                    privateKey,
                    EvidenceContract.class
                );
            TransactionReceipt receipt =
                evidenceContractWriter.createEvidenceWithExtraKey(
                    hashByteList,
                    signerList,
                    sigList,
                    logList,
                    timestampList,
                    extraKeyList
                );

            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceContract.CreateEvidenceEventResponse> eventList =
                evidenceContractWriter.getCreateEvidenceEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(StringUtils.EMPTY,
                    ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(StringUtils.EMPTY,
                    ErrorCode.CREDENTIAL_EVIDENCE_ALREADY_EXISTS, info);
            } else {
                for (EvidenceContract.CreateEvidenceEventResponse event : eventList) {
                    if (event.sig.equalsIgnoreCase(signature)
                        && event.signer.equalsIgnoreCase(address)) {
                        return new ResponseData<>(hashValue, ErrorCode.SUCCESS, info);
                    }
                }
            }
            return new ResponseData<>(StringUtils.EMPTY,
                ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT);
        } catch (Exception e) {
            logger.error("create evidence failed due to system error. ", e);
            return new ResponseData<>(StringUtils.EMPTY, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    /* (non-Javadoc)
     * @see com.webank.weid.service.impl.engine.EvidenceServiceEngine#getInfoByCustomKey(
     * java.lang.String)
     */
    @Override
    public ResponseData<EvidenceInfo> getInfoByCustomKey(String extraKey) {

        if (StringUtils.isBlank(extraKey) || !DataToolUtils.isUtf8String(extraKey)) {
            logger.error("[getInfoByCustomKey] extraKey illegal. ");
            return new ResponseData<EvidenceInfo>(null, ErrorCode.ILLEGAL_INPUT);
        }
        try {
            String hash = DataToolUtils.convertHashByte32ArrayIntoHashStr(
                evidenceContract.getHashByExtraKey(extraKey));
            if (StringUtils.isBlank(hash)) {
                logger.error("[getInfoByCustomKey] extraKey dose not match any hash. ");
                return new ResponseData<EvidenceInfo>(null,
                    ErrorCode.CREDENTIAL_EVIDENCE_NOT_EXIST);
            }
            return this.getInfo(hash);
        } catch (Exception e) {
            logger.error("[getInfoByCustomKey] get evidence info failed. ", e);
            return new ResponseData<EvidenceInfo>(null, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    @Override
    public ResponseData<Boolean> setAttribute(
        String hashValue,
        String key,
        String value,
        Long timestamp,
        String privateKey
    ) {
        try {
            List<byte[]> hashByteList = new ArrayList<>();
            if (!DataToolUtils.isValidHash(hashValue)) {
                return new ResponseData<>(false, ErrorCode.ILLEGAL_INPUT, null);
            }
            hashByteList.add(DataToolUtils.convertHashStrIntoHashByte32Array(hashValue));
            List<String> keyList = new ArrayList<>();
            keyList.add(key);
            List<String> valueList = new ArrayList<>();
            valueList.add(value);
            List<BigInteger> timestampList = new ArrayList<>();
            timestampList.add(new BigInteger(String.valueOf(timestamp), 10));
            /*String address = WeIdUtils
                .convertWeIdToAddress(DataToolUtils.convertPrivateKeyToDefaultWeId(privateKey));*/

            String address = DataToolUtils.addressFromPrivate(new BigInteger(privateKey));
            List<String> signerList = new ArrayList<>();
            signerList.add(address);
            EvidenceContract evidenceContractWriter =
                reloadContract(
                    this.evidenceAddress,
                    privateKey,
                    EvidenceContract.class
                );
            TransactionReceipt receipt =
                evidenceContractWriter.setAttribute(
                    hashByteList,
                    signerList,
                    keyList,
                    valueList,
                    timestampList
                );
            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceExtraAttributeChangedEventResponse> eventList =
                evidenceContractWriter.getEvidenceExtraAttributeChangedEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_NOT_EXIST, info);
            } else {
                for (EvidenceExtraAttributeChangedEventResponse event : eventList) {
                    if (event.signer.equalsIgnoreCase(address)) {
                        return new ResponseData<>(true, ErrorCode.SUCCESS, info);
                    }
                }
            }
            return new ResponseData<>(false,
                ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT);
        } catch (Exception e) {
            logger.error("add log failed due to system error. ", e);
            return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    @Override
    public ResponseData<Boolean> revoke(
            String hashValue,
            Boolean revokeStage,
            Long timestamp,
            String privateKey
    ) {
        try {
            String address = DataToolUtils.addressFromPrivate(new BigInteger(privateKey));
            EvidenceContract evidenceContractWriter =
                    reloadContract(
                            this.evidenceAddress,
                            privateKey,
                            EvidenceContract.class
                    );
            TransactionReceipt receipt =
                    evidenceContractWriter.revoke(
                            DataToolUtils.convertHashStrIntoHashByte32Array(hashValue),
                            address,
                            revokeStage
                    );
            TransactionInfo info = new TransactionInfo(receipt);
            List<EvidenceContract.RevokeEventResponse> eventList =
                    evidenceContractWriter.getRevokeEvents(receipt);
            if (eventList == null) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            } else if (eventList.isEmpty()) {
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_NOT_EXIST, info);
            } else {
                for (EvidenceContract.RevokeEventResponse event : eventList) {
                    if (event.signer.equalsIgnoreCase(address)) {
                        return new ResponseData<>(true, ErrorCode.SUCCESS, info);
                    }
                }
            }
            return new ResponseData<>(false,
                    ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT);
        } catch (Exception e) {
            logger.error("add log failed due to system error. ", e);
            return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }
}
