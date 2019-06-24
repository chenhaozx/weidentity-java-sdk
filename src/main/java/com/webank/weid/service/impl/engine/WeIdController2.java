package com.webank.weid.service.impl.engine;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.fisco.bcos.web3j.abi.EventEncoder;
import org.fisco.bcos.web3j.crypto.ECKeyPair;
import org.fisco.bcos.web3j.crypto.Keys;
import org.fisco.bcos.web3j.protocol.core.DefaultBlockParameterNumber;
import org.fisco.bcos.web3j.protocol.core.methods.response.BcosBlock;
import org.fisco.bcos.web3j.protocol.core.methods.response.BcosTransactionReceipt;
import org.fisco.bcos.web3j.protocol.core.methods.response.Log;
import org.fisco.bcos.web3j.protocol.core.methods.response.Transaction;
import org.fisco.bcos.web3j.protocol.core.methods.response.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webank.weid.constant.ErrorCode;
import com.webank.weid.constant.ResolveEventLogStatus;
import com.webank.weid.constant.WeIdConstant;
import com.webank.weid.constant.WeIdEventConstant;
import com.webank.weid.contract.v2.WeIdContract;
import com.webank.weid.contract.v2.WeIdContract.WeIdAttributeChangedEventResponse;
import com.webank.weid.exception.DataTypeCastException;
import com.webank.weid.exception.PrivateKeyIllegalException;
import com.webank.weid.exception.ResolveAttributeException;
import com.webank.weid.protocol.base.AuthenticationProperty;
import com.webank.weid.protocol.base.PublicKeyProperty;
import com.webank.weid.protocol.base.ServiceProperty;
import com.webank.weid.protocol.base.WeIdDocument;
import com.webank.weid.protocol.base.WeIdPrivateKey;
import com.webank.weid.protocol.base.WeIdPublicKey;
import com.webank.weid.protocol.request.CreateWeIdArgs;
import com.webank.weid.protocol.request.SetAuthenticationArgs;
import com.webank.weid.protocol.request.SetPublicKeyArgs;
import com.webank.weid.protocol.request.SetServiceArgs;
import com.webank.weid.protocol.response.CreateWeIdDataResult;
import com.webank.weid.protocol.response.EngineResultData;
import com.webank.weid.protocol.response.ResolveEventLogResult;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.protocol.response.TransactionInfo;
import com.webank.weid.util.DataToolUtils;
import com.webank.weid.util.DateUtils;
import com.webank.weid.util.WeIdUtils;

/**
 * @author tonychen 2019年6月21日
 *
 */
public class WeIdController2 implements WeIdController{

	private static final Logger logger = LoggerFactory.getLogger(WeIdController2.class);
	
	 /**
     * The topic map.
     */
    private static final HashMap<String, String> topicMap;
	
	/**
     * Block number for stopping parsing.
     */
    private static final int STOP_RESOLVE_BLOCK_NUMBER = 0;
    
	 /**
     * WeIdentity DID contract object, for calling weIdentity DID contract.
     */
    private static WeIdContract weIdContract;
    
    static {
        // initialize the event topic
        topicMap = new HashMap<String, String>();

        topicMap.put(
            EventEncoder.encode(WeIdContract.WEIDATTRIBUTECHANGED_EVENT),
            WeIdEventConstant.WEID_EVENT_ATTRIBUTE_CHANGE
        );
    }
    
	/* (non-Javadoc)
	 * @see com.webank.weid.service.engine.WeIdController#createWeId()
	 */
	@Override
	public EngineResultData<CreateWeIdDataResult> createWeId() {
		CreateWeIdDataResult result = new CreateWeIdDataResult();
        ECKeyPair keyPair = null;

        try {
            keyPair = Keys.createEcKeyPair();
        } catch (Exception e) {
            logger.error("Create weId failed.", e);
            return new EngineResultData<>(null, ErrorCode.WEID_KEYPAIR_CREATE_FAILED);
        }

        String publicKey = String.valueOf(keyPair.getPublicKey());
        String privateKey = String.valueOf(keyPair.getPrivateKey());
        WeIdPublicKey userWeIdPublicKey = new WeIdPublicKey();
        userWeIdPublicKey.setPublicKey(publicKey);
        result.setUserWeIdPublicKey(userWeIdPublicKey);
        WeIdPrivateKey userWeIdPrivateKey = new WeIdPrivateKey();
        userWeIdPrivateKey.setPrivateKey(privateKey);
        result.setUserWeIdPrivateKey(userWeIdPrivateKey);
        String weId = WeIdUtils.convertPublicKeyToWeId(publicKey);
        result.setWeId(weId);
        EngineResultData<Boolean> innerRespData = processCreateWeId(weId, publicKey, privateKey);

        if (innerRespData.getErrorCode() != ErrorCode.SUCCESS.getCode()) {
            logger.error(
                "[createWeId] Create weId failed. error message is :{}",
                innerRespData.getErrorMessage()
            );
            return new EngineResultData<>(null,
                ErrorCode.getTypeByErrorCode(innerRespData.getErrorCode()),
                innerRespData.getTransactionInfo());
        }
        return new EngineResultData<>(result,
            ErrorCode.getTypeByErrorCode(innerRespData.getErrorCode()),
            innerRespData.getTransactionInfo());
	}

	/* (non-Javadoc)
	 * @see com.webank.weid.service.engine.WeIdController#createWeId(com.webank.weid.protocol.request.CreateWeIdArgs)
	 */
	@Override
	public EngineResultData<String> createWeId(CreateWeIdArgs createWeIdArgs) {
		 if (createWeIdArgs == null) {
	            logger.error("[createWeId]: input parameter createWeIdArgs is null.");
	            return new EngineResultData<>(StringUtils.EMPTY, ErrorCode.ILLEGAL_INPUT);
	        }
	        if (!WeIdUtils.isPrivateKeyValid(createWeIdArgs.getWeIdPrivateKey())) {
	            return new EngineResultData<>(StringUtils.EMPTY, ErrorCode.WEID_PRIVATEKEY_INVALID);
	        }
	        String privateKey = createWeIdArgs.getWeIdPrivateKey().getPrivateKey();
	        String publicKey = createWeIdArgs.getPublicKey();
	        if (StringUtils.isNotBlank(publicKey)) {
	            if (!WeIdUtils.isKeypairMatch(privateKey, publicKey)) {
	                return new EngineResultData<>(
	                    StringUtils.EMPTY,
	                    ErrorCode.WEID_PUBLICKEY_AND_PRIVATEKEY_NOT_MATCHED
	                );
	            }
	            String weId = WeIdUtils.convertPublicKeyToWeId(publicKey);
	            EngineResultData<Boolean> isWeIdExistResp = this.isWeIdExist(weId);
	            if (isWeIdExistResp.getResult() == null || isWeIdExistResp.getResult()) {
	                logger
	                    .error("[createWeId]: create weid failed, the weid :{} is already exist", weId);
	                return new EngineResultData<>(StringUtils.EMPTY, ErrorCode.WEID_ALREADY_EXIST);
	            }

	            EngineResultData<Boolean> innerRespData = processCreateWeId(weId, publicKey, privateKey);
	            if (innerRespData.getErrorCode() != ErrorCode.SUCCESS.getCode()) {
	                logger.error(
	                    "[createWeId]: create weid failed. error message is :{}, public key is {}",
	                    innerRespData.getErrorMessage(),
	                    publicKey
	                );
	                return new EngineResultData<>(StringUtils.EMPTY,
	                    ErrorCode.getTypeByErrorCode(innerRespData.getErrorCode()),
	                    innerRespData.getTransactionInfo());
	            }
	            return new EngineResultData<>(weId,
	                ErrorCode.getTypeByErrorCode(innerRespData.getErrorCode()),
	                innerRespData.getTransactionInfo());
	        } else {
	            return new EngineResultData<>(StringUtils.EMPTY, ErrorCode.WEID_PUBLICKEY_INVALID);
	        }
	        
	        
	}

	private EngineResultData<Boolean> processCreateWeId(String weId, String publicKey,
	        String privateKey) {
		try {
//            WeIdContract weIdContract = (WeIdContract) reloadContract(
//                weIdContractAddress,
//                privateKey,
//                WeIdContract.class);

            String weAddress = WeIdUtils.convertWeIdToAddress(weId);
            String auth = new StringBuffer()
                .append(publicKey)
                .append(WeIdConstant.SEPARATOR)
                .append(weAddress)
                .toString();
            String created = DateUtils.getCurrentTimeStampString();
            TransactionReceipt receipt = weIdContract.createWeId(
                weAddress,
                DataToolUtils.stringToByteArray(auth),
                DataToolUtils.stringToByteArray(created),
                BigInteger.valueOf(DateUtils.getCurrentTimeStamp())
            ).send();

            TransactionInfo info = new TransactionInfo(receipt);
            List<WeIdAttributeChangedEventResponse> response =
                weIdContract.getWeIdAttributeChangedEvents(receipt);
            if (CollectionUtils.isEmpty(response)) {
                logger.error(
                    "The input private key does not match the current weid, operation of "
                        + "modifying weid is not allowed. weid is {}",
                    weId
                );
                return new EngineResultData<>(false, ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH, info);
            }
            return new EngineResultData<>(true, ErrorCode.SUCCESS, info);
        } catch (PrivateKeyIllegalException e) {
            return new EngineResultData<>(false, e.getErrorCode());
        } catch (Exception e) {
            logger.error("createWeId failed,e", e);
            return new EngineResultData<>(false, ErrorCode.UNKNOW_ERROR);
        }
	    }

	/* (non-Javadoc)
	 * @see com.webank.weid.service.impl.engine.WeIdController#isWeIdExist(java.lang.String)
	 */
	@Override
	public EngineResultData<Boolean> isWeIdExist(String weId) {
		 try {
	            boolean isExist = weIdContract
	                .isIdentityExist(WeIdUtils.convertWeIdToAddress(weId)).send().booleanValue();
	            return new EngineResultData<>(isExist, ErrorCode.SUCCESS);
	        } catch (Exception e) {
	            logger.error("[isWeIdExist] execute failed. Error message :{}", e);
	            return new EngineResultData<>(false, ErrorCode.UNKNOW_ERROR);
	        }
	}

	/* (non-Javadoc)
	 * @see com.webank.weid.service.impl.engine.WeIdController#setPublicKey(com.webank.weid.protocol.request.SetPublicKeyArgs)
	 */
	@Override
	public EngineResultData<Boolean> setPublicKey(SetPublicKeyArgs setPublicKeyArgs) {
		 String weId = setPublicKeyArgs.getWeId();
	        String weAddress = WeIdUtils.convertWeIdToAddress(weId);
	        if (StringUtils.isEmpty(weAddress)) {
	            logger.error("setPublicKey: weId : {} is invalid.", weId);
	            return new EngineResultData<>(false, ErrorCode.WEID_INVALID);
	        }
	        String owner = setPublicKeyArgs.getOwner();
	        if (StringUtils.isEmpty(owner)) {
	            owner = weAddress;
	        } else {
	            if (WeIdUtils.isWeIdValid(owner)) {
	                owner = WeIdUtils.convertWeIdToAddress(owner);
	            } else {
	                logger.error("setPublicKey: owner : {} is invalid.", owner);
	                return new EngineResultData<>(false, ErrorCode.WEID_INVALID);
	            }
	        }
	        String pubKey = setPublicKeyArgs.getPublicKey();
	        String attributeKey =
	            new StringBuffer()
	                .append(WeIdConstant.WEID_DOC_PUBLICKEY_PREFIX)
	                .append(WeIdConstant.SEPARATOR)
	                .append(setPublicKeyArgs.getType())
	                .append(WeIdConstant.SEPARATOR)
	                .append("base64")
	                .toString();
	        String privateKey = setPublicKeyArgs.getUserWeIdPrivateKey().getPrivateKey();
	        try {
//	            WeIdContract weIdContract = (WeIdContract) reloadContract(
//	                weIdContractAddress,
//	                privateKey,
//	                WeIdContract.class
//	            );
	            byte[] attrValue = new StringBuffer().append(pubKey).append("/").append(owner)
	                .toString().getBytes();
	            BigInteger updated = BigInteger.valueOf(System.currentTimeMillis());
	            TransactionReceipt transactionReceipt =
	                weIdContract.setAttribute(
	                    weAddress,
	                    DataToolUtils.stringToByte32Array(attributeKey),
	                    attrValue,
	                    updated
	                ).send();

	            TransactionInfo info = new TransactionInfo(transactionReceipt);
	            List<WeIdAttributeChangedEventResponse> response =
	                weIdContract.getWeIdAttributeChangedEvents(transactionReceipt);
	            if (CollectionUtils.isNotEmpty(response)) {
	                return new EngineResultData<>(true, ErrorCode.SUCCESS, info);
	            } else {
	                return new EngineResultData<>(false, ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH,
	                    info);
	            }
	        } catch (PrivateKeyIllegalException e) {
	            return new EngineResultData<>(false, e.getErrorCode());
	        } catch (Exception e) {
	            return new EngineResultData<>(false, ErrorCode.UNKNOW_ERROR);
	        }
	}

	/* (non-Javadoc)
	 * @see com.webank.weid.service.impl.engine.WeIdController#setService(com.webank.weid.protocol.request.SetServiceArgs)
	 */
	@Override
	public EngineResultData<Boolean> setService(SetServiceArgs setServiceArgs) {
		String weId = setServiceArgs.getWeId();
        String serviceType = setServiceArgs.getType();
        String serviceEndpoint = setServiceArgs.getServiceEndpoint();
        if (WeIdUtils.isWeIdValid(weId)) {
            String privateKey = setServiceArgs.getUserWeIdPrivateKey().getPrivateKey();
            try {
//                WeIdContract weIdContract = (WeIdContract) reloadContract(
//                    weIdContractAddress,
//                    privateKey,
//                    WeIdContract.class);
                String attrKey = new StringBuffer()
                    .append(WeIdConstant.WEID_DOC_SERVICE_PREFIX)
                    .append("/")
                    .append(serviceType)
                    .toString();
                TransactionReceipt receipt =
                    weIdContract.setAttribute(
                        WeIdUtils.convertWeIdToAddress(weId),
                        DataToolUtils.stringToByte32Array(attrKey),
                        serviceEndpoint.getBytes(),
                        BigInteger.valueOf(System.currentTimeMillis())
                    ).send();

                TransactionInfo info = new TransactionInfo(receipt);
                List<WeIdAttributeChangedEventResponse> response =
                    weIdContract.getWeIdAttributeChangedEvents(receipt);
                if (CollectionUtils.isNotEmpty(response)) {
                    return new EngineResultData<>(true, ErrorCode.SUCCESS, info);
                } else {
                    return new EngineResultData<>(false, ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH,
                        info);
                }
            } catch (PrivateKeyIllegalException e) {
                return new EngineResultData<>(false, e.getErrorCode());
            } catch (Exception e) {
                logger.error("Set weId service failed. Error message :{}", e);
                return new EngineResultData<>(false, ErrorCode.UNKNOW_ERROR);
            }
        } else {
            return new EngineResultData<>(false, ErrorCode.WEID_INVALID);
        }
	}

	/* (non-Javadoc)
	 * @see com.webank.weid.service.impl.engine.WeIdController#setAuthentication(com.webank.weid.protocol.request.SetAuthenticationArgs)
	 */
	@Override
	public EngineResultData<Boolean> setAuthentication(SetAuthenticationArgs setAuthenticationArgs) {
		String weId = setAuthenticationArgs.getWeId();
        if (WeIdUtils.isWeIdValid(weId)) {
            String weAddress = WeIdUtils.convertWeIdToAddress(weId);

            String owner = setAuthenticationArgs.getOwner();
            if (StringUtils.isEmpty(owner)) {
                owner = weAddress;
            } else {
                if (WeIdUtils.isWeIdValid(owner)) {
                    owner = WeIdUtils.convertWeIdToAddress(owner);
                } else {
                    logger.error("[setAuthentication]: owner : {} is invalid.", owner);
                    return new EngineResultData<>(false, ErrorCode.WEID_INVALID);
                }
            }
            String privateKey = setAuthenticationArgs.getUserWeIdPrivateKey().getPrivateKey();
            try {
//                WeIdContract weIdContract = (WeIdContract) reloadContract(
//                    weIdContractAddress,
//                    privateKey,
//                    WeIdContract.class);

                byte[] attrValue = new StringBuffer()
                    .append(setAuthenticationArgs.getPublicKey())
                    .append("/")
                    .append(owner)
                    .toString().getBytes();
                TransactionReceipt receipt = weIdContract.setAttribute(
                    weAddress,
                    DataToolUtils.stringToByte32Array(WeIdConstant.WEID_DOC_AUTHENTICATE_PREFIX),
                    attrValue,
                    BigInteger.valueOf(System.currentTimeMillis())
                ).send();
                TransactionInfo info = new TransactionInfo(receipt);
                List<WeIdAttributeChangedEventResponse> response =
                    weIdContract.getWeIdAttributeChangedEvents(receipt);
                if (CollectionUtils.isNotEmpty(response)) {
                    return new EngineResultData<>(true, ErrorCode.SUCCESS, info);
                } else {
                    logger.error("Set authenticate failed. Error message :{}",
                        ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH.getCodeDesc());
                    return new EngineResultData<>(false, ErrorCode.WEID_PRIVATEKEY_DOES_NOT_MATCH);
                }
            } catch (PrivateKeyIllegalException e) {
                logger.error("Set authenticate with private key exception. Error message :{}", e);
                return new EngineResultData<>(false, e.getErrorCode());
            } catch (Exception e) {
                logger.error("Set authenticate failed. Error message :{}", e);
                return new EngineResultData<>(false, ErrorCode.UNKNOW_ERROR);
            }
        } else {
            logger.error("Set authenticate failed. weid : {} is invalid.", weId);
            return new EngineResultData<>(false, ErrorCode.WEID_INVALID);
        }
	}

	/* (non-Javadoc)
	 * @see com.webank.weid.service.impl.engine.WeIdController#getWeIdDocument(java.lang.String)
	 */
	@Override
	public EngineResultData<WeIdDocument> getWeIdDocument(String weId) {
		 WeIdDocument result = new WeIdDocument();
	        result.setId(weId);
	        int latestBlockNumber = 0;
	        try {
	            String identityAddr = WeIdUtils.convertWeIdToAddress(weId);
	            latestBlockNumber = weIdContract
	                .getLatestRelatedBlock(identityAddr).send().intValue();
	            if (0 == latestBlockNumber) {
	                return new EngineResultData<>(null, ErrorCode.WEID_DOES_NOT_EXIST);
	            }

	            resolveTransaction(weId, latestBlockNumber, result);
//	            responseData.setResult(result);
	            return new EngineResultData<WeIdDocument>(result, ErrorCode.SUCCESS);
	        } catch (InterruptedException | ExecutionException e) {
	            logger.error("Set weId service failed. Error message :{}", e);
	            return new EngineResultData<>(null, ErrorCode.TRANSACTION_EXECUTE_ERROR);
	        } catch (TimeoutException e) {
	            return new EngineResultData<>(null, ErrorCode.TRANSACTION_TIMEOUT);
	        } catch (ResolveAttributeException e) {
	            logger.error("[getWeIdDocument]: resolveTransaction failed. "
	                    + "weId: {}, errorCode:{}",
	                weId,
	                e.getErrorCode(),
	                e);
	            return new EngineResultData<WeIdDocument>(result, ErrorCode.getTypeByErrorCode(e.getErrorCode()));
	        } catch (Exception e) {
	            logger.error("[getWeIdDocument]: exception.", e);
	            return new EngineResultData<>(null, ErrorCode.UNKNOW_ERROR);
	        }
	}

	   private static ResolveEventLogResult resolveAttributeEvent(
		        String weId,
		        TransactionReceipt receipt,
		        WeIdDocument result) {

		        List<WeIdAttributeChangedEventResponse> eventlog =
		            weIdContract.getWeIdAttributeChangedEvents(receipt);
		        ResolveEventLogResult response = new ResolveEventLogResult();

		        if (CollectionUtils.isEmpty(eventlog)) {
		            response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_EVENTLOG_NULL);
		            return response;
		        }

		        int previousBlock = 0;
		        for (WeIdAttributeChangedEventResponse res : eventlog) {
		            if (res.identity == null || res.updated == null || res.previousBlock == null) {
		                response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_RES_NULL);
		                return response;
		            }

		            String identity = res.identity.toString();
		            if (result.getUpdated() == null) {
		                long timeStamp = res.updated.longValue();
		                result.setUpdated(timeStamp);
		            }
		            String weAddress = WeIdUtils.convertWeIdToAddress(weId);
		            if (!StringUtils.equals(weAddress, identity)) {
		                response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_WEID_NOT_MATCH);
		                return response;
		            }

		            String key = new String(res.key);
		            String value = new String(res.value);
		            previousBlock = res.previousBlock.intValue();
		            buildupWeIdAttribute(key, value, weId, result);
		        }

		        response.setPreviousBlock(previousBlock);
		        response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_SUCCESS);
		        return response;
		    }

		    private static void buildupWeIdAttribute(
		        String key, String value, String weId, WeIdDocument result) {
		        if (StringUtils.startsWith(key, WeIdConstant.WEID_DOC_PUBLICKEY_PREFIX)) {
		            buildWeIdPublicKeys(value, weId, result);
		        } else if (StringUtils.startsWith(key, WeIdConstant.WEID_DOC_AUTHENTICATE_PREFIX)) {
		            buildWeIdPublicKeys(value, weId, result);
		            buildWeIdAuthentication(value, weId, result);
		        } else if (StringUtils.startsWith(key, WeIdConstant.WEID_DOC_SERVICE_PREFIX)) {
		            buildWeIdService(key, value, weId, result);
		        } else {
		            buildWeIdAttributeDefault(key, value, weId, result);
		        }
		    }

		    private static void buildWeIdPublicKeys(String value, String weId, WeIdDocument result) {

		        logger.info("method buildWeIdPublicKeys() parameter::value:{}, weId:{}, "
		            + "result:{}", value, weId, result);
		        List<PublicKeyProperty> pubkeyList = result.getPublicKey();
		        for (PublicKeyProperty pr : pubkeyList) {
		            if (StringUtils.contains(value, pr.getPublicKey())) {
		                return;
		            }
		        }
		        PublicKeyProperty pubKey = new PublicKeyProperty();
		        pubKey.setId(
		            new StringBuffer()
		                .append(weId)
		                .append("#keys-")
		                .append(result.getPublicKey().size())
		                .toString()
		        );
		        String[] publicKeyData = StringUtils.splitByWholeSeparator(value, "/");
		        if (publicKeyData != null && publicKeyData.length == 2) {
		            pubKey.setPublicKey(publicKeyData[0]);
		            String weAddress = publicKeyData[1];
		            String owner = WeIdUtils.convertAddressToWeId(weAddress);
		            pubKey.setOwner(owner);
		        }
		        result.getPublicKey().add(pubKey);
		    }

		    private static void buildWeIdAuthentication(String value, String weId, WeIdDocument result) {

		        logger.info("method buildWeIdAuthentication() parameter::value:{}, weId:{}, "
		            + "result:{}", value, weId, result);
		        AuthenticationProperty auth = new AuthenticationProperty();
		        List<PublicKeyProperty> keys = result.getPublicKey();
		        List<AuthenticationProperty> authList = result.getAuthentication();

		        for (PublicKeyProperty r : keys) {
		            if (StringUtils.contains(value, r.getPublicKey())) {
		                for (AuthenticationProperty ar : authList) {
		                    if (StringUtils.equals(ar.getPublicKey(), r.getId())) {
		                        return;
		                    }
		                }
		                auth.setPublicKey(r.getId());
		                result.getAuthentication().add(auth);
		            }
		        }
		    }

		    private static void buildWeIdService(String key, String value, String weId,
		        WeIdDocument result) {

		        logger.info("method buildWeIdService() parameter::key{}, value:{}, weId:{}, "
		            + "result:{}", key, value, weId, result);
		        String service = StringUtils.splitByWholeSeparator(key, "/")[2];
		        List<ServiceProperty> serviceList = result.getService();
		        for (ServiceProperty sr : serviceList) {
		            if (StringUtils.equals(service, sr.getType())) {
		                return;
		            }
		        }
		        ServiceProperty serviceResult = new ServiceProperty();
		        serviceResult.setType(service);
		        serviceResult.setServiceEndpoint(value);
		        result.getService().add(serviceResult);
		    }

		    private static void buildWeIdAttributeDefault(
		        String key, String value, String weId, WeIdDocument result) {

		        logger.info("method buildWeIdAttributeDefault() parameter::key{}, value:{}, weId:{}, "
		            + "result:{}", key, value, weId, result);
		        switch (key) {
		            case WeIdConstant.WEID_DOC_CREATED:
		                result.setCreated(Long.valueOf(value));
		                break;
		            default:
		                break;
		        }
		    }

		    private static ResolveEventLogResult resolveEventLog(
		        String weId, Log log, TransactionReceipt receipt, WeIdDocument result) {
		        String topic = log.getTopics().get(0);
		        String event = topicMap.get(topic);

		        if (StringUtils.isNotBlank(event)) {
		            switch (event) {
		                case WeIdConstant.WEID_EVENT_ATTRIBUTE_CHANGE:
		                    return resolveAttributeEvent(weId, receipt, result);
		                default:
		            }
		        }
		        ResolveEventLogResult response = new ResolveEventLogResult();
		        response.setResolveEventLogStatus(ResolveEventLogStatus.STATUS_EVENT_NULL);
		        return response;
		    }

		    private static void resolveTransaction(
		        String weId,
		        int blockNumber,
		        WeIdDocument result) {

		        int previousBlock = blockNumber;
		        while (previousBlock != STOP_RESOLVE_BLOCK_NUMBER) {
		            int currentBlockNumber = previousBlock;
		            BcosBlock latestBlock = null;
		            try {
		                latestBlock =
		                    getWeb3j()
		                        .getBlockByNumber(
		                            new DefaultBlockParameterNumber(currentBlockNumber),
		                            true
		                        )
		                        .send();
		            } catch (IOException e) {
		                logger.error(
		                    "[resolveTransaction]:get block by number :{} failed. Exception message:{}",
		                    currentBlockNumber,
		                    e
		                );
		            }
		            if (latestBlock == null) {
		                logger.info(
		                    "[resolveTransaction]:get block by number :{} . latestBlock is null",
		                    currentBlockNumber
		                );
		                return;
		            }
		            List<Transaction> transList =
		                latestBlock
		                    .getBlock()
		                    .getTransactions()
		                    .stream()
		                    .map(transactionResult -> (Transaction) transactionResult.get())
		                    .collect(Collectors.toList());

		            previousBlock = 0;
		            try {
		                for (Transaction transaction : transList) {
		                    String transHash = transaction.getHash();

		                    BcosTransactionReceipt rec1 = getWeb3j().getTransactionReceipt(transHash)
		                        .send();
		                    TransactionReceipt receipt = rec1.getTransactionReceipt().get();
		                    List<Log> logs = rec1.getResult().getLogs();
		                    for (Log log : logs) {
		                        ResolveEventLogResult returnValue =
		                            resolveEventLog(weId, log, receipt, result);
		                        if (returnValue.getResultStatus().equals(
		                            ResolveEventLogStatus.STATUS_SUCCESS)) {
		                            if (returnValue.getPreviousBlock() == currentBlockNumber) {
		                                continue;
		                            }
		                            previousBlock = returnValue.getPreviousBlock();
		                        }
		                    }
		                }
		            } catch (IOException | DataTypeCastException e) {
		                logger.error(
		                    "[resolveTransaction]: get TransactionReceipt by weId :{} failed.",
		                    weId,
		                    e
		                );
		                throw new ResolveAttributeException(
		                    ErrorCode.TRANSACTION_EXECUTE_ERROR.getCode(),
		                    ErrorCode.TRANSACTION_EXECUTE_ERROR.getCodeDesc());
		            }
		        }
		    }
}