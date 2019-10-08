#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{get_arg, revert, Error};

#[no_mangle]
pub extern "C" fn call() {
    let account_number: [u8; 32] = get_arg(0).unwrap().unwrap();
    let number: u32 = get_arg(1).unwrap().unwrap();

    let account_sum: u8 = account_number.iter().sum();
    let total_sum: u32 = u32::from(account_sum) + number;

    revert(Error::User(total_sum as u16));
}