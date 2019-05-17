/*
 *       Copyright© (2018-2019) WeBank Co., Ltd.
 *
 *       This file is part of weidentity-java-sdk.
 *
 *       weidentity-java-sdk is free software: you can redistribute it and/or modify
 *       it under the terms of the GNU Lesser General Public License as published by
 *       the Free Software Foundation, either version 3 of the License, or
 *       (at your option) any later version.
 *
 *       weidentity-java-sdk is distributed in the hope that it will be useful,
 *       but WITHOUT ANY WARRANTY; without even the implied warranty of
 *       MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *       GNU Lesser General Public License for more details.
 *
 *       You should have received a copy of the GNU Lesser General Public License
 *       along with weidentity-java-sdk.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.webank.weid.service.impl;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.StringUtils;
import org.fisco.bcos.web3j.abi.datatypes.Address;
import org.fisco.bcos.web3j.crypto.Keys;
import org.fisco.bcos.web3j.crypto.Sign;
import org.fisco.bcos.web3j.crypto.Sign.SignatureData;
import org.fisco.bcos.web3j.protocol.core.methods.response.TransactionReceipt;
import org.fisco.bcos.web3j.tuples.generated.Tuple6;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.webank.weid.config.ContractConfig;
import com.webank.weid.constant.ErrorCode;
import com.webank.weid.constant.WeIdConstant;
import com.webank.weid.contract.Evidence;
import com.webank.weid.contract.EvidenceFactory;
import com.webank.weid.contract.EvidenceFactory.CreateEvidenceLogEventResponse;
import com.webank.weid.protocol.base.Credential;
import com.webank.weid.protocol.base.EvidenceInfo;
import com.webank.weid.protocol.base.WeIdDocument;
import com.webank.weid.protocol.base.WeIdPrivateKey;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.rpc.EvidenceService;
import com.webank.weid.rpc.WeIdService;
import com.webank.weid.service.BaseService;
import com.webank.weid.util.CredentialUtils;
import com.webank.weid.util.DataToolUtils;
import com.webank.weid.util.WeIdUtils;

/**
 * Service implementations for operations on Evidence.
 *
 * @author chaoxinhu 2019.1
 */
@Component
public class EvidenceServiceImpl extends BaseService implements EvidenceService {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceServiceImpl.class);

    // Evidence Factory contract instance
    private static EvidenceFactory evidenceFactory;

    // Evidence Factory contract address
    private static String evidenceFactoryAddress;

    private WeIdService weIdService = new WeIdServiceImpl();

    /**
     * Instantiates a new evidence service impl.
     */
    public EvidenceServiceImpl() {
        init();
    }

    private static void init() {
        ContractConfig config = context.getBean(ContractConfig.class);
        evidenceFactoryAddress = config.getEvidenceAddress();
        evidenceFactory = (EvidenceFactory) getContractService(
            evidenceFactoryAddress,
            EvidenceFactory.class);
    }

    /**
     * Create a new evidence to the blockchain and store its address into the credential.
     */
    @Override
    public ResponseData<String> createEvidence(
        Credential credential,
        WeIdPrivateKey weIdPrivateKey) {

        ErrorCode innerResponse = CredentialUtils
            .isCreateEvidenceArgsValid(credential, weIdPrivateKey);
        if (ErrorCode.SUCCESS.getCode() != innerResponse.getCode()) {
            logger.error("Create Evidence input format error!");
            return new ResponseData<>(
                StringUtils.EMPTY,
                innerResponse
            );
        }

        innerResponse = CredentialUtils.isCredentialValid(credential);
        if (ErrorCode.SUCCESS.getCode() != innerResponse.getCode()) {
            logger.error("Create Evidence input format error: credential!");
            return new ResponseData<>(
                StringUtils.EMPTY,
                innerResponse);
        }

        try {
            String credentialHash = CredentialUtils.getCredentialHash(credential);
            String credentialHashOnChain = credentialHash
                .replaceAll(WeIdConstant.HEX_PREFIX, StringUtils.EMPTY);
            List<byte[]> hashAttributes = new ArrayList<>();
            hashAttributes.add(
                credentialHashOnChain.substring(0, WeIdConstant.BYTES32_FIXED_LENGTH).getBytes());
            hashAttributes.add(
                credentialHashOnChain.substring(
                    WeIdConstant.BYTES32_FIXED_LENGTH,
                    WeIdConstant.BYTES32_FIXED_LENGTH * 2
                ).getBytes());
            List<byte[]> extraValueList = new ArrayList<>();
            extraValueList.add(StringUtils.EMPTY.getBytes());
            Sign.SignatureData sigData = DataToolUtils
                .signMessage(credentialHash, weIdPrivateKey.getPrivateKey());
            List<String> signer = new ArrayList<>();
            signer.add(Keys.getAddress(DataToolUtils
                .createKeyPairFromPrivate(new BigInteger(weIdPrivateKey.getPrivateKey()))));
            TransactionReceipt receipt = evidenceFactory.createEvidence(
                hashAttributes,
                signer,
                sigData.getR(),
                sigData.getS(),
                BigInteger.valueOf(sigData.getV()),
                extraValueList
            ).send();

            List<CreateEvidenceLogEventResponse> eventResponseList =
            		evidenceFactory.getCreateEvidenceLogEvents(receipt);
            CreateEvidenceLogEventResponse event = eventResponseList.get(0);

            if (event != null) {
                innerResponse = verifyCreateEvidenceEvent(event);
                if (ErrorCode.SUCCESS.getCode() != innerResponse.getCode()) {
                    return new ResponseData<>(
                        StringUtils.EMPTY,
                        innerResponse
                    );
                }
                return new ResponseData<>(event.addr.toString(), ErrorCode.SUCCESS);
            } else {
                logger
                    .error(
                        "create evidence failed due to transcation event decoding failure. ");
                return new ResponseData<>(StringUtils.EMPTY,
                    ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
            }
        } catch (TimeoutException e) {
            logger.error("create evidencefailed due to system timeout. ", e);
            return new ResponseData<>(StringUtils.EMPTY, ErrorCode.TRANSACTION_TIMEOUT);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("create evidence failed due to transaction error. ", e);
            return new ResponseData<>(StringUtils.EMPTY, ErrorCode.TRANSACTION_EXECUTE_ERROR);
        } catch (Exception e) {
            logger.error("create evidence failed due to system error. ", e);
            return new ResponseData<>(StringUtils.EMPTY, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }


    /**
     * Get the evidence from blockchain.
     *
     * @param evidenceAddress the evidence address on chain
     * @return The EvidenceInfo
     */
    @Override
    public ResponseData<EvidenceInfo> getEvidence(String evidenceAddress) {
        ResponseData<EvidenceInfo> responseData = new ResponseData<>();
        if (StringUtils.isEmpty(evidenceAddress) || !WeIdUtils.isValidAddress(evidenceAddress)) {
            logger.error("Evidence argument illegal input: address. ");
            return new ResponseData<>(null, ErrorCode.ILLEGAL_INPUT);
        }

        Evidence evidence = (Evidence) getContractService(evidenceAddress, Evidence.class);

        try {
        	Tuple6<List<byte[]>,List<String>,List<byte[]>,List<byte[]>,List<BigInteger>,List<byte[]>> rawResult =
                evidence.getInfo().send();
            if (rawResult == null) {
                return new ResponseData<>(null, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
            }

            List<byte[]> credentialHashList = rawResult.getValue1();
            List<String> issuerList = rawResult.getValue2();

            EvidenceInfo evidenceInfoData = new EvidenceInfo();
            evidenceInfoData.setCredentialHash(
                WeIdConstant.HEX_PREFIX + new String(credentialHashList.get(0))
                    + new String(credentialHashList.get(1)));

            List<String> signerStringList = new ArrayList<>();
            for (String addr : issuerList) {
                signerStringList.add(addr);
            }
            evidenceInfoData.setSigners(signerStringList);

            List<String> signaturesList = new ArrayList<>();
            List<byte[]> rlist = rawResult.getValue3();
            List<byte[]> slist = rawResult.getValue4();
            List<BigInteger> vlist = rawResult.getValue5();
            byte v;
            byte[] r;
            byte[] s;
            for (int index = 0; index < rlist.size(); index++) {
                v = (byte) (vlist.get(index).intValue());
                r = rlist.get(index);
                s = slist.get(index);
                SignatureData sigData = new SignatureData(v, r, s);
                signaturesList.add(new String(
                		DataToolUtils
                        .base64Encode(DataToolUtils.simpleSignatureSerialization(sigData)),
                    StandardCharsets.UTF_8)
                );
            }
            evidenceInfoData.setSignatures(signaturesList);

            responseData.setResult(evidenceInfoData);
            return responseData;
        } catch (TimeoutException e) {
            logger.error("get evidence failed due to system timeout. ", e);
            return new ResponseData<>(null, ErrorCode.TRANSACTION_TIMEOUT);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("get evidence failed due to transaction error. ", e);
            return new ResponseData<>(null, ErrorCode.TRANSACTION_EXECUTE_ERROR);
        } catch (Exception e) {
            logger.error("get evidence failed.", e);
            return new ResponseData<>(null, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }

    /**
     * Verify a Credential based on its Evidence info. A Credential might contain multiple evidence
     * addresses. Anyone successfully verified will lead to a true outcome.
     *
     * @param credential the args
     * @return true if succeeds, false otherwise
     */
    @Override
    public ResponseData<Boolean> verify(Credential credential, String evidenceAddress) {
        ErrorCode innerResponse = CredentialUtils
            .isCredentialValid(credential);
        if (ErrorCode.SUCCESS.getCode() != innerResponse.getCode()) {
            logger.error("Verify EvidenceInfo input illegal: credential");
            return new ResponseData<>(
                false,
                innerResponse
            );
        }
        if (!WeIdUtils.isValidAddress(evidenceAddress)) {
            logger.error("Verify EvidenceInfo input illegal: evidenceInfo address");
            return new ResponseData<>(false, ErrorCode.ILLEGAL_INPUT);
        }

        // Step 1: Get EvidenceInfo from chain
        ResponseData<EvidenceInfo> innerEvidenceResponseData = getEvidence(evidenceAddress);
        if (innerEvidenceResponseData.getResult() == null) {
            return new ResponseData<>(
                false,
                ErrorCode.getTypeByErrorCode(innerEvidenceResponseData.getErrorCode())
            );
        }

        EvidenceInfo evidenceInfo = innerEvidenceResponseData.getResult();

        // Step 2: Verify Hash value
        String hashOffChain = CredentialUtils.getCredentialHash(credential);
        if (!StringUtils.equalsIgnoreCase(hashOffChain, evidenceInfo.getCredentialHash())) {
            logger.error(
                "credential hash mismatches. Off-chain: {}, on-chain: {}", hashOffChain,
                evidenceInfo.getCredentialHash());
            return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_HASH_MISMATCH);
        }

        // Step 3: Verify each signature value in EvidenceInfo wrt the signer based on their their
        // publickeys from WeIDContract. Here each signature/signer pair must pass the verification.
        try {
            for (int i = 0; i < evidenceInfo.getSignatures().size(); i++) {
                String signer = evidenceInfo.getSigners().get(i);
                String signature = evidenceInfo.getSignatures().get(i);
                if (WeIdUtils.isEmptyAddress(new Address(signer))) {
                    break;
                }
                SignatureData signatureData =
                	DataToolUtils.simpleSignatureDeserialization(
                			DataToolUtils.base64Decode(
                            signature.getBytes(StandardCharsets.UTF_8)));

                ResponseData<Boolean> innerResponseData = verifySignatureToSigner(
                    hashOffChain,
                    WeIdUtils.convertAddressToWeId(signer),
                    signatureData
                );
                if (!innerResponseData.getResult()) {
                    return innerResponseData;
                }
            }
        } catch (Exception e) {
            logger.error(
                "Generic error occurred during verify evidenceInfo: ", e);
            return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
        return new ResponseData<>(true, ErrorCode.SUCCESS);
    }

    private ErrorCode verifyCreateEvidenceEvent(CreateEvidenceLogEventResponse event) {
        if (event.retCode == null || event.addr == null) {
            return ErrorCode.ILLEGAL_INPUT;
        }
        Integer eventRetCode = event.retCode.intValue();
        if (eventRetCode
            .equals(ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT.getCode())) {
            return ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT;
        }
        return ErrorCode.SUCCESS;
    }

    private ResponseData<Boolean> verifySignatureToSigner(String rawData,
        String signerWeId, SignatureData signatureData) {

        try {
            ResponseData<WeIdDocument> innerResponseData =
                weIdService.getWeIdDocument(signerWeId);
            if (innerResponseData.getErrorCode() != ErrorCode.SUCCESS.getCode()) {
                logger.error(
                    "Error occurred when fetching WeIdentity DID document for: {}, msg: {}",
                    signerWeId, innerResponseData.getErrorMessage());
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_WEID_DOCUMENT_ILLEGAL);
            }
            WeIdDocument weIdDocument = innerResponseData.getResult();
            ErrorCode errorCode = DataToolUtils
                .verifySignatureFromWeId(rawData, signatureData, weIdDocument);
            if(errorCode.getCode() != ErrorCode.SUCCESS.getCode()) {
            	return new ResponseData<>(false, errorCode);
            }
            return new ResponseData<>(true, ErrorCode.SUCCESS);
        } catch (Exception e) {
            logger.error("error occurred during verifying signatures from chain: ", e);
            return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
        }
    }
}
