package org.tron.core.actuator;

import com.google.common.math.LongMath;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.db.AccountStore;
import org.tron.core.db.Manager;
import org.tron.core.db.VotesStore;
import org.tron.core.db.WitnessStore;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.VoteWitnessContract;
import org.tron.protos.Contract.VoteWitnessContract.Vote;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class VoteWitnessActuator extends AbstractActuator {

  VoteWitnessActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    try {
      VoteWitnessContract voteContract = contract.unpack(VoteWitnessContract.class);
      countVoteAccount(voteContract);
      ret.setStatus(fee, code.SUCESS);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  @Override
  public boolean validate() throws ContractValidateException {
    try {
      if (!contract.is(VoteWitnessContract.class)) {
        throw new ContractValidateException(
            "contract type error,expected type [VoteWitnessContract],real type[" + contract
                .getClass() + "]");
      }
      if (this.dbManager == null) {
        throw new ContractValidateException("dbManager is null");
      }

      VoteWitnessContract contract = this.contract.unpack(VoteWitnessContract.class);
      if (!Wallet.addressValid(contract.getOwnerAddress().toByteArray())) {
        throw new ContractValidateException("Invalidate address");
      }
      ByteString ownerAddress = contract.getOwnerAddress();
      byte[] ownerAddressBytes = ownerAddress.toByteArray();
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);

      AccountStore accountStore = dbManager.getAccountStore();
      WitnessStore witnessStore = dbManager.getWitnessStore();

      if (contract.getVotesCount() == 0) {
        throw new ContractValidateException(
            "VoteNumber must more than 0");
      }
      if (contract.getVotesCount() > dbManager.getDynamicPropertiesStore().getMaxVoteNumber()) {
        throw new ContractValidateException(
            "VoteNumber more than maxVoteNumber " + dbManager.getDynamicPropertiesStore()
                .getMaxVoteNumber());
      }

      Iterator<Vote> iterator = contract.getVotesList().iterator();
      Long sum = 0L;
      while (iterator.hasNext()) {
        Vote vote = iterator.next();
        byte[] witnessCandidate = vote.getVoteAddress().toByteArray();
        if (!Wallet.addressValid(witnessCandidate)){
          throw new ContractValidateException("Invalidate vote address!");
        }
        long voteCount = vote.getVoteCount();
        if (voteCount <= 0) {
          throw new ContractValidateException("vote count must be greater than 0");
        }
        String readableWitnessAddress = StringUtil.createReadableString(vote.getVoteAddress());
        if (!accountStore.has(witnessCandidate)) {
          throw new ContractValidateException(
              "Account[" + readableWitnessAddress + "] not exists");
        }
        if (!witnessStore.has(witnessCandidate)) {
          throw new ContractValidateException(
              "Witness[" + readableWitnessAddress + "] not exists");
        }
        sum = LongMath.checkedAdd(sum, vote.getVoteCount());
      }

      if (!accountStore.has(ownerAddressBytes)) {
        throw new ContractValidateException(
            "Account[" + readableOwnerAddress + "] not exists");
      }

      long tronPower = accountStore.get(ownerAddressBytes).getTronPower();
      sum = LongMath.checkedMultiply(sum, 1000000L); //trx -> drop. The vote count is based on TRX
      if (sum > tronPower) {
        throw new ContractValidateException(
            "The total number of votes[" + sum + "] is greater than the tronPower[" + tronPower
                + "]");
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }

    return true;
  }

  private void countVoteAccount(VoteWitnessContract voteContract) {
    ByteString ownerAddress = voteContract.getOwnerAddress();
    byte[] ownerAddressBytes = ownerAddress.toByteArray();

    VotesCapsule votesCapsule;
    VotesStore votesStore = dbManager.getVotesStore();
    AccountStore accountStore = dbManager.getAccountStore();

    AccountCapsule accountCapsule = accountStore.get(ownerAddressBytes);

    if (!votesStore.has(ownerAddressBytes)) {
      votesCapsule = new VotesCapsule(ownerAddress, accountCapsule.getVotesList());
    } else {
      votesCapsule = votesStore.get(ownerAddressBytes);
    }

    accountCapsule.clearVotes();
    votesCapsule.clearNewVotes();

    voteContract.getVotesList().forEach(vote -> {
      logger.debug("countVoteAccount,address[{}]",
          ByteArray.toHexString(vote.getVoteAddress().toByteArray()));

      votesCapsule.addNewVotes(vote.getVoteAddress(), vote.getVoteCount());
      accountCapsule.addVotes(vote.getVoteAddress(), vote.getVoteCount());
    });

    accountStore.put(accountCapsule.createDbKey(), accountCapsule);
    votesStore.put(ownerAddressBytes, votesCapsule);
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(VoteWitnessContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
