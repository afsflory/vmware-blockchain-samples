import json
from web3 import Web3
from solcx import compile_standard
from web3.middleware import geth_poa_middleware
import time
# Solidity source code
compiled_sol = compile_standard({
    "language": "Solidity",
    "sources": {
        "Greeter.sol": {
            "content": '''
//                pragma solidity ^0.5.0;
                contract Greeter {
                  string public greeting;
                  constructor() public {
                      greeting = 'Hello';
                  }
                  function setGreeting(string memory _greeting) public {
                      greeting = _greeting;
                  }
                  function greet() view public returns (string memory) {
                      return greeting;
                  }
                }
              '''
        }
    },
    "settings":
        {
            "outputSelection": {
                "*": {
                    "*": [
                        "metadata", "evm.bytecode"
                        , "evm.bytecode.sourceMap"
                    ]
                }
            }
        }
})
account1 = '0xf17f52151EbEF6C7334FAD080c5704D77216b732'
key1 = '0xae6ae8e5ccbfb04590405997ee2d52d2b330726137b875053c36d94e974d162f'
# web3.py instance
#w3 = Web3(Web3.EthereumTesterProvider())
#w3 = Web3(Web3.HTTPProvider("http://54.160.229.176:8545"))
# w3 = Web3(Web3.HTTPProvider('http://50.18.241.122:32010')) #besu single cluster
# w3 = Web3(Web3.HTTPProvider('http://50.18.241.122:32010')) #besu single cluster
w3 = Web3(Web3.HTTPProvider('https://localhost:8545', request_kwargs={'verify': False})) #besu multi cluster
w3.middleware_onion.inject(geth_poa_middleware, layer=0)
# get bytecode
bytecode = compiled_sol['contracts']['Greeter.sol']['Greeter']['evm']['bytecode']['object']
# get abi
abi = json.loads(compiled_sol['contracts']['Greeter.sol']['Greeter']['metadata'])['output']['abi']
Greeter = w3.eth.contract(abi=abi, bytecode=bytecode)
# Submit the transaction that deploys the contract
construct_txn = Greeter.constructor().buildTransaction({
    'from': account1,
    'gas': 2000000,
    'gasPrice': 234567897654321,
    'nonce': w3.eth.getTransactionCount(account1),
    'chainId': 5000
})
signed_txn = w3.eth.account.sign_transaction(construct_txn, key1)
tx_hash = w3.eth.sendRawTransaction(signed_txn.rawTransaction)
tx_receipt = w3.eth.waitForTransactionReceipt(tx_hash)
print(tx_receipt)
greeter = w3.eth.contract(
    address=tx_receipt.contractAddress,
    abi=abi
)
print(greeter.functions.greet().call())
count = 10000
while (count > 0):
    construct_txn = greeter.functions.setGreeting('Nihao').buildTransaction({
        'from': account1,
        'gas': 2000000,
        'gasPrice': 234567897654321,
        'nonce': w3.eth.getTransactionCount(account1),
        'chainId': 5000
    })
    signed_txn = w3.eth.account.sign_transaction(construct_txn, key1)
    tx_hash = w3.eth.sendRawTransaction(signed_txn.rawTransaction)
    tx_receipt = w3.eth.waitForTransactionReceipt(tx_hash)
    print("count:", count)
    print(tx_receipt)
    print(greeter.functions.greet().call())
    count -= 1
    time.sleep(1)
