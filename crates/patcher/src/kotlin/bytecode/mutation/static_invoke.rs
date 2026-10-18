// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

use reseam_apk::reseam_dex::{
    CodeItem, Instruction as DexInsn, MethodIdx, RegList, find_contiguous_free_registers,
};

use crate::kotlin::convert::kotlin_to_dex;
use crate::kotlin::types::{Instruction, MethodRef};

pub(super) fn lower_static_invoke(
    code: &CodeItem,
    index: usize,
    method: MethodIdx,
    proto: &str,
    registers: &[u16],
) -> Result<Vec<DexInsn>, String> {
    let input = Instruction::Invoke(crate::kotlin::types::InvokeInsn {
        opcode: 0x71,
        registers: registers.to_vec(),
        method: MethodRef {
            defining_class: "Ljava/lang/Object;".into(),
            name: "call".into(),
            proto: proto.into(),
        },
    });
    let words = crate::kotlin::invoke::scratch_words(&input).map_err(|error| error.to_string())?;
    let scratch = if words == 0 {
        Vec::new()
    } else {
        find_contiguous_free_registers(code, index, words, registers)
            .ok_or_else(|| format!("no contiguous scratch span at instruction {index}"))?
    };
    let lowered = crate::kotlin::invoke::lower_instruction(input, scratch)?;
    lowered
        .iter()
        .map(|instruction| match instruction {
            Instruction::Invoke(value) => Ok(DexInsn::InvokeStatic {
                method,
                args: RegList::try_from_iter(
                    value.registers.iter().map(|register| *register as u8),
                )
                .map_err(|error| error.to_string())?,
            }),
            Instruction::InvokeRange(value) => Ok(DexInsn::InvokeStaticRange {
                method,
                first_reg: value.start_reg,
                count: value.reg_count as u8,
            }),
            Instruction::Reg2(value) => {
                // Typed moves have no pool operands.
                let mut empty =
                    reseam_apk::reseam_dex::DexFile::new(reseam_apk::reseam_dex::DexHeader::new(
                        reseam_apk::reseam_dex::DexVersion::V035,
                    ));
                kotlin_to_dex(&Instruction::Reg2(*value), &mut empty, None)
                    .map_err(|error| error.to_string())
            }
            _ => Err("invoke lowering produced an unsupported instruction".into()),
        })
        .collect()
}
