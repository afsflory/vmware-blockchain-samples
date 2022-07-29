package com.vmware.ethereum.service;

/*-
 * #%L
 * ERC-20 Load Testing Tool
 * %%
 * Copyright (C) 2021 VMware
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import static java.math.BigInteger.valueOf;
import static java.util.Objects.requireNonNull;
import static org.springframework.util.ReflectionUtils.findField;
import static org.springframework.util.ReflectionUtils.setField;

import com.vmware.ethereum.config.PermissioningConfig;
import com.vmware.ethereum.config.TokenConfig;
import com.vmware.ethereum.config.Web3jConfig;
import com.vmware.ethereum.config.WorkloadConfig;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.model.SecurityToken;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.BatchTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.StaticGasProvider;
import org.web3j.tx.response.TransactionReceiptProcessor;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecureTokenApi {

  private final TokenConfig config;
  private final WorkloadConfig workloadConfig;
  private final Web3jConfig web3jConfig;
  private final PermissioningConfig permissioningConfig;
  private final ArrayList<Web3j> web3j;
  private final TransactionManager transactionManager;
  private final TransactionReceiptProcessor transactionReceiptProcessor;
  private final SecureTokenFactory tokenFactory;
  private final PermissioningApi permissioningApi;
  private final String deployerAddress;
  private final Credentials deployerCredentials;
  private final ArrayList<String> senderAddressArray;
  private final ArrayList<Credentials> credentialsArray;
  private SecurityToken token;
  private BigInteger gasEstimate;
  private BigInteger gasPrice;
  private String contractAddress;
  private ContractGasProvider gasProvider;
  private final ArrayList<SecurityToken> tokenArray = new ArrayList<>();

  @PostConstruct
  public void init() throws IOException {
    log.info("Client version: {}", getClientVersion());
    log.info("Net version: {}", getNetVersion());
    log.info("Deployer address: {}", deployerAddress);

    setGas();
    log.info("Gas Estimate {}", gasEstimate);
    log.info("Gas Price {}", gasPrice);
    try {
      if (!permissioningConfig.getContractAddress().isEmpty()
          && !permissioningApi.checkPermission(
              deployerAddress,
              permissioningConfig.getContractAddress(),
              BigInteger.valueOf(3),
              deployerCredentials)) {
        deployerPermission();
      }
    } catch (Exception e) {
      log.error("permissioning for deployer error = {}", e.toString());
    }
    token = tokenFactory.getSecureToken(gasEstimate, gasPrice);
    token.getTransactionReceipt().ifPresent(receipt -> log.info("Receipt: {}", receipt));
    contractAddress = token.getContractAddress();
    for (int i = 0; i < workloadConfig.getSenders(); i++) {
      try {
        token.transfer(senderAddressArray.get(i), new BigInteger("100000000000000")).send();
      } catch (Exception e) {
        log.error("Error is transfer from deployer to sender - {}", e.getMessage());
      }
      tokenArray.add(
          SecurityToken.load(contractAddress, web3j.get(0), credentialsArray.get(i), gasProvider));
    }
    setTransactionManager();
  }

  /**
   * This is a hack. We want contract deployment to use PollingTransactionReceiptProcessor, but
   * override it later for token transfers to use NoOpProcessor or
   * QueuingTransactionReceiptProcessor if configured.
   */
  private void setTransactionManager() {
    Field field = requireNonNull(findField(SecurityToken.class, "transactionManager"));
    field.setAccessible(true);
    setField(field, token, transactionManager);
  }

  /** Sets gasEstimate and gasPrice. */
  private void setGas() throws IOException {
    Transaction tx = Transaction.createEthCallTransaction(null, null, null);
    gasEstimate = web3j.get(0).ethEstimateGas(tx).send().getAmountUsed();
    gasPrice = web3j.get(0).ethGasPrice().send().getGasPrice();
    gasProvider = new StaticGasProvider(gasPrice, gasEstimate);
  }

  public ContractGasProvider getGasProvider() {
    return gasProvider;
  }

  /** Function to give deployer Permission */
  private void deployerPermission() throws Exception {
    permissioningApi.getPermission(
        deployerAddress,
        permissioningConfig.getContractAddress(),
        BigInteger.valueOf(3),
        Credentials.create(permissioningConfig.getSuperAdmins()[0]));
    TransactionReceipt approveTxReceipt;
    for (int i = 0; i < permissioningConfig.getSuperAdmins().length - 1; i++) {
      approveTxReceipt =
          permissioningApi.approvePermission(
              deployerAddress,
              permissioningConfig.getContractAddress(),
              BigInteger.valueOf(3),
              Credentials.create(permissioningConfig.getSuperAdmins()[i]));
      log.debug("approve deploy perm tx receipt - {}", approveTxReceipt);
    }
    Boolean checkDeployer =
        permissioningApi.checkPermission(
            deployerAddress,
            permissioningConfig.getContractAddress(),
            BigInteger.valueOf(3),
            deployerCredentials);
    log.info("deployer permission given to {} : {}", deployerAddress, checkDeployer);
  }

  /** Creates token transfer transaction request. */
  public RawTransaction createTransaction(
      SecurityToken tokenBatched, BatchTransactionManager batchTransactionManager)
      throws IOException {
    String txData =
        tokenBatched
            .transfer(config.getRecipient(), valueOf(config.getAmount()))
            .encodeFunctionCall();
    log.debug("txData - {}", txData);
    return batchTransactionManager.sendTransactionRequest(
        gasPrice, gasEstimate, contractAddress, txData, BigInteger.ZERO);
  }

  /** Adds transaction request to the batch. */
  @SneakyThrows(IOException.class)
  public void addBatchRequests(
      BatchRequest batchRequest,
      ArrayList<String> signedBatchRequest,
      SecurityToken tokenBatched,
      Web3j web3jBatched,
      Credentials credentialsBatched) {
    BatchTransactionManager batchTransactionManager =
        new BatchTransactionManager(
            web3jBatched,
            credentialsBatched,
            web3jConfig.getEthClient().getChainId(),
            transactionReceiptProcessor);
    RawTransaction rawTransaction = createTransaction(tokenBatched, batchTransactionManager);
    String signedTransaction = batchTransactionManager.signTransactionRequest(rawTransaction);
    signedBatchRequest.add(Hash.sha3(signedTransaction));
    batchRequest.add(
        batchTransactionManager.sendSignedTransactionRequest(signedTransaction, web3jBatched));
    log.debug("batched-requests {}", batchRequest.getRequests());
  }

  /** Transfer Batched transactions asynchronously. */
  public CompletableFuture<BatchResponse> transferBatchAsync(BatchRequest batchRequest) {
    log.debug("inside transfer batch async");
    return batchRequest.sendAsync();
  }

  public String getContractAddress() {
    return contractAddress;
  }

  public String getNetVersion() {
    try {
      return web3j.get(0).netVersion().send().getNetVersion();
    } catch (Exception e) {
      log.warn("{}", e.getMessage());
      return "Unknown";
    }
  }

  public String getClientVersion() {
    try {
      return web3j.get(0).web3ClientVersion().send().getWeb3ClientVersion();
    } catch (Exception e) {
      log.warn("{}", e.getMessage());
      return "Unknown";
    }
  }

  /** Get current block number. */
  public long getBlockNumber() {
    try {
      return web3j.get(0).ethBlockNumber().send().getBlockNumber().longValue();
    } catch (Exception e) {
      log.warn("{}", e.getMessage());
      return 0;
    }
  }

  /** Get token balance of the sender. */
  public long getSenderBalance() {
    return getBalance(deployerAddress);
  }

  /** Get token balance of the sender. */
  public ArrayList<Long> getSenderArrayBalance(ArrayList<String> senderArray) {
    ArrayList<Long> senderArrayBalance = new ArrayList<>();
    for (String sender : senderArray) {
      senderArrayBalance.add(getBalance(sender));
    }

    return senderArrayBalance;
  }

  /** Get token balance of the recipients. */
  public long getRecipientBalance() {
    return getBalance(config.getRecipient());
  }

  /** Get token balance of the given address. */
  private long getBalance(String account) {
    try {
      return token.balanceOf(account).send().longValue();
    } catch (Exception e) {
      log.warn("{}", e.getMessage());
      return 0;
    }
  }
}
