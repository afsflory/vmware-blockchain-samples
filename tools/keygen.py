# run this script for generating new ethereum account private keys and addresses
from web3.auto import w3
acct = w3.eth.account.create('KEYSMASH FJAFJKLDSKF7JKFDJ 1530')
print(acct.address)
print(acct.privateKey.hex())
