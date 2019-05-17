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

package com.webank.weid.contract.deploy;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.StringUtils;
import org.fisco.bcos.channel.client.Service;
import org.fisco.bcos.web3j.crypto.Credentials;
import org.fisco.bcos.web3j.crypto.gm.GenCredential;
import org.fisco.bcos.web3j.protocol.Web3j;
import org.fisco.bcos.web3j.protocol.channel.ChannelEthereumService;
import org.fisco.bcos.web3j.tx.gas.StaticGasProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.webank.weid.constant.WeIdConstant;
import com.webank.weid.contract.AuthorityIssuerController;
import com.webank.weid.contract.AuthorityIssuerData;
import com.webank.weid.contract.CommitteeMemberData;
import com.webank.weid.contract.CptController;
import com.webank.weid.contract.CptData;
import com.webank.weid.contract.EvidenceFactory;
import com.webank.weid.contract.RoleController;
import com.webank.weid.contract.WeIdContract;

/**
 * The Class DeployContract.
 *
 * @author tonychen
 */
public class DeployContract {

    /**
     * log4j.
     */
    private static final Logger logger = LoggerFactory.getLogger(DeployContract.class);

    /**
     * The context.
     */
    protected static final ApplicationContext context;

    /**
     * The credentials.
     */
    private static Credentials credentials;

    /**
     * web3j object.
     */
    private static Web3j web3j;

    static {
        context = new ClassPathXmlApplicationContext("applicationContext.xml");
    }

    /**
     * The main method.
     *
     * @param args the arguments
     */
    public static void main(String[] args) {

        deployContract();
        System.exit(0);
    }

    /**
     * Load config.
     *
     * @return true, if successful
     */
    private static boolean loadConfig() {

        Service service = context.getBean(Service.class);
        try {
            service.run();
        } catch (Exception e) {
            logger.error("[BaseService] Service init failed. ", e);
        }

        ChannelEthereumService channelEthereumService = new ChannelEthereumService();
        channelEthereumService.setChannelService(service);
        web3j = Web3j.build(channelEthereumService, service.getGroupId());
        if (web3j == null) {
            logger.error("[BaseService] web3j init failed. ");
            return false;
        }

        
//        ECKeyPair keyPair = null;
//
//        try {
//            keyPair = Keys.createEcKeyPair();
//        } catch (Exception e) {
//            logger.error("Create weId failed.", e);
//            return false;
//        }

//        String publicKey = String.valueOf(keyPair.getPublicKey());
//        String privateKey = String.valueOf(keyPair.getPrivateKey());
        
        //将公私钥输出到output
//        credentials = Credentials.create(keyPair);
        credentials = GenCredential.create();

        if (null == credentials) {
            logger.error("[BaseService] credentials init failed.");
            return false;
        }

//        logger.info("begin init credentials");
//        credentials = GenCredential.create(toolConf.getPrivKey());

        if (credentials == null) {
            logger.error("[BaseService] credentials init failed. ");
            return false;
        }

        return true;
    }

    /**
     * Gets the web3j.
     *
     * @return the web3j instance
     */
    protected static Web3j getWeb3j() {
        if (web3j == null) {
            loadConfig();
        }
        return web3j;
    }

    private static void deployContract() {
        String weIdContractAddress = deployWeIdContract();
        String authorityIssuerDataAddress = deployAuthorityIssuerContracts();
        deployCptContracts(authorityIssuerDataAddress, weIdContractAddress);
        deployEvidenceContracts();
    }

    private static String deployWeIdContract() {
        if (web3j == null) {
            loadConfig();
        }
        
        
        //根据合约地址加载合约
        
        WeIdContract weIdContract = null;
		try {
			weIdContract = WeIdContract.deploy(
					web3j, 
					credentials, 
					new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT))
					.send();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return StringUtils.EMPTY;
		}

            String contractAddress = weIdContract.getContractAddress();
            writeAddressToFile(contractAddress, "weIdContract.address");
            return contractAddress;
        
    }

    private static String deployCptContracts(
        String authorityIssuerDataAddress, String weIdContractAddress) {
        if (web3j == null) {
            loadConfig();
        }

        try {
            CptData cptData =
            		CptData.deploy(
            				web3j, 
            				credentials, 
            				new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT), 
            				authorityIssuerDataAddress)
            				.send();
            String cptDataAddress = cptData.getContractAddress();
            //        writeAddressToFile("CptData", cptDataAddress);

            CptController cptController =
                CptController.deploy(
                		web3j, 
                		credentials, 
                		new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT), 
                		cptDataAddress,weIdContractAddress)
                .send();
         
            String cptControllerAddress = cptController.getContractAddress();
            writeAddressToFile(cptControllerAddress, "cptController.address");
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("CptController deploy exception", e);
        } catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

        return StringUtils.EMPTY;
    }

    private static String deployAuthorityIssuerContracts() {
        if (web3j == null) {
            loadConfig();
        }

        // Step 1: Deploy RoleController sol => [addr1]
        String authorityIssuerDataAddress = StringUtils.EMPTY;
        try {
        RoleController roleController =
            RoleController.deploy(
					web3j, 
					credentials, 
					new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT))
					.send();

        // Step 2: Deploy CommitteeMemberData sol => [addr1]
        String roleControllerAddress = StringUtils.EMPTY;
            roleControllerAddress = roleController.getContractAddress();
            CommitteeMemberData committeeMemberData = CommitteeMemberData.deploy(
            		web3j, 
					credentials, 
					new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT),
                roleControllerAddress).send();

        // Step 4: Deploy AuthorityIssuerData sol => [addr1]
        AuthorityIssuerData  authorityIssuerData = 
        		AuthorityIssuerData.deploy(
        				web3j, 
				credentials, 
				new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT),
                roleControllerAddress
            ).send();

        

        // Step 5: Deploy AuthorityIssuerController sol => [addr1]
            authorityIssuerDataAddress = authorityIssuerData.getContractAddress();
//            AuthorityIssuerController.de
            AuthorityIssuerController authorityIssuerController = AuthorityIssuerController.deploy(
            		web3j, 
    				credentials, 
    				new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT),
                authorityIssuerDataAddress,
                roleControllerAddress).send();
            String authorityIssuerControllerAddress =
                    authorityIssuerController.getContractAddress();
                writeAddressToFile(authorityIssuerControllerAddress, "authorityIssuer.address");
                return authorityIssuerControllerAddress;
            
        } catch (Exception e) {
            logger.error("CommitteeMemberController deployment error:", e);
            return StringUtils.EMPTY;
        }

    }

    private static String deployEvidenceContracts() {
        if (web3j == null) {
            loadConfig();
        }
        try {
            EvidenceFactory evidenceFactory =
                EvidenceFactory.deploy(
                		web3j, 
        				credentials, 
        				new StaticGasProvider(WeIdConstant.GAS_PRICE, WeIdConstant.GAS_LIMIT)
                ).send();
            String evidenceFactoryAddress = evidenceFactory.getContractAddress();
            writeAddressToFile(evidenceFactoryAddress, "evidenceController.address");
            return evidenceFactoryAddress;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("EvidenceFactory deploy exception", e);
        } catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        return StringUtils.EMPTY;
    }

    private static void writeAddressToFile(
        String contractAddress,
        String fileName) {

        OutputStreamWriter ow = null;
        try {
            boolean flag = true;
            File file = new File(fileName);
            if (file.exists()) {
                flag = file.delete();
            }
            if (!flag) {
                logger.error("writeAddressToFile() delete file is fail.");
                return;
            }
            ow = new OutputStreamWriter(
                new FileOutputStream(fileName, true),
                StandardCharsets.UTF_8
            );
            String content = new StringBuffer().append(contractAddress).toString();
            ow.write(content);
            ow.close();
        } catch (IOException e) {
            logger.error("writer file exception", e);
        } finally {
            if (ow != null) {
                try {
                    ow.close();
                } catch (IOException e) {
                    logger.error("io close exception", e);
                }
            }
        }
    }
}
