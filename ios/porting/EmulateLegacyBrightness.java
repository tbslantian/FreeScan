/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import java.nio.file.*;

public class EmulateLegacyBrightness extends GhidraScript {
    public void run() throws Exception {
        if (!"16ccda64341bb023c90e65478ebc574ebb2229221c345d9b7886001b8bb82694".equalsIgnoreCase(currentProgram.getExecutableSHA256()) ||
            currentProgram.getImageBase().getOffset()!=0x100000L)
            throw new Exception("The selected artifact or analysis image base does not match this test.");
        int checked=0,totalSteps=0;
        for(int[] interval:new int[][]{{7,250},{0,76},{96,165},{0,255}}) {
            EmulatorHelper emulator=new EmulatorHelper(currentProgram);
            try {
                for(int register=0;register<31;register++) emulator.writeRegister("x"+register,0);
                if(currentProgram.getRegister("d8")!=null) emulator.writeRegister("d8",0);
                long mat=0x20001000L,data=0x20010000L,limits=0x20002000L,thread=0x20003000L;
                int rows=3,cols=256,stride=cols*3+7;
                byte[] source=new byte[rows*stride];java.util.Arrays.fill(source,(byte)0xee);
                for(int y=0;y<rows;y++) for(int x=0;x<cols;x++) for(int c=0;c<3;c++)
                    source[y*stride+x*3+c]=(byte)((x+y*17+c*73)&255);
                emulator.writeMemory(toAddr(data),source);
                emulator.writeMemoryValue(toAddr(mat),4,0x42ff4010);
                emulator.writeMemoryValue(toAddr(mat+8),4,rows);
                emulator.writeMemoryValue(toAddr(mat+12),4,cols);
                emulator.writeMemoryValue(toAddr(mat+16),8,data);
                emulator.writeMemoryValue(toAddr(mat+72),8,mat+80);
                emulator.writeMemoryValue(toAddr(mat+80),8,stride);
                emulator.writeMemoryValue(toAddr(limits),4,interval[0]);
                emulator.writeMemoryValue(toAddr(limits+4),4,interval[1]);
                emulator.writeMemoryValue(toAddr(limits+8),4,interval[1]-interval[0]);
                emulator.writeMemoryValue(toAddr(thread+40),8,0x12345678);
                emulator.writeRegister("sp",0x30010000L);emulator.writeRegister("x30",0x40000000L);
                emulator.writeRegister("x2",mat);emulator.writeRegister("x3",limits);
                emulator.writeRegister("x4",limits+4);emulator.writeRegister("x5",limits+8);
                emulator.writeRegister(emulator.getPCRegister(),0x001e31a0L);
                int steps=0;
                while(emulator.getExecutionAddress().getOffset()!=0x40000000L) {
                    Address pc=emulator.getExecutionAddress();long offset=pc.getOffset();
                    if(offset==0x001ba750L || offset==0x001bcf30L || offset==0x001bb6c0L) {
                        // Model a single OpenMP worker; bounds/stride are already
                        // initialized by the original worker's instructions.
                        if(offset==0x001ba750L) emulator.writeRegister("x0",0);
                        emulator.writeRegister(emulator.getPCRegister(),emulator.readRegister("x30"));continue;
                    }
                    Instruction instruction=currentProgram.getListing().getInstructionAt(pc);
                    if(instruction!=null && instruction.getMnemonicString().equalsIgnoreCase("mrs") &&
                       instruction.getDefaultOperandRepresentation(1).toLowerCase().contains("tpidr_el0")) {
                        emulator.writeRegister(instruction.getDefaultOperandRepresentation(0),thread);
                        emulator.writeRegister(emulator.getPCRegister(),offset+4);continue;
                    }
                    if(++steps>500000) throw new Exception("Worker emulation exceeded its instruction bound at "+pc);
                    if(!emulator.step(monitor)) throw new Exception("Worker emulation failed at "+pc+": "+emulator.getLastError());
                }
                byte[] actual=emulator.readMemory(toAddr(data),source.length);
                for(int y=0;y<rows;y++) for(int x=0;x<cols;x++) for(int c=0;c<3;c++) {
                    int index=y*stride+x*3+c,value=Byte.toUnsignedInt(source[index]);
                    int expected=(int)((Math.max(interval[0],Math.min(interval[1],value))-interval[0])*255.0/(interval[1]-interval[0]));
                    if(Byte.toUnsignedInt(actual[index])!=expected) throw new Exception("Original worker disagrees at input "+value+" with interval "+interval[0]+".."+interval[1]);
                    checked++;
                }
                for(int y=0;y<rows;y++) for(int i=cols*3;i<stride;i++)
                    if(actual[y*stride+i]!=source[y*stride+i]) throw new Exception("Original worker changed row padding");
                totalSteps+=steps;
            } finally {emulator.dispose();}
        }
        String result="Original ARM64 brightness worker matched "+checked+" channel samples in "+totalSteps+" emulated instructions; row padding preserved.";
        Files.writeString(Path.of(getScriptArgs()[0]),result+"\n");println(result);
    }
}
