/**
 * Copyright 2014-2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.webank.weid.full.cpt;

import com.webank.weid.protocol.cpt.v2.*;
import com.webank.weid.util.DataToolUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class TestGenerateSchema {

    @Test
    public void testJjschema() throws IOException {
        String Cpt101 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt101.class);
        String Cpt102 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt102.class);
        String Cpt103 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt103.class);
        String Cpt104 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt104.class);
        String Cpt105 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt105.class);
        String Cpt106 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt106.class);
        String Cpt107 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt107.class);
        String Cpt108 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt108.class);
        String Cpt109 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt109.class);
        String Cpt110 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt110.class);
        String Cpt111 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt111.class);
        String Cpt11 = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt11.class);
        String Cpt11Salt = DataToolUtils.generateDefaultCptJsonSchema(
            com.webank.weid.protocol.cpt.v2.Cpt11Salt.class);

        String path = "D:\\projects\\weid\\WeIdentity\\src\\test\\resources\\default_cpt\\";
        Files.write(Paths.get(path + "Cpt101" + ".json"), Cpt101.getBytes());
        Files.write(Paths.get(path + "Cpt102" + ".json"), Cpt102.getBytes());
        Files.write(Paths.get(path + "Cpt103" + ".json"), Cpt103.getBytes());
        Files.write(Paths.get(path + "Cpt104" + ".json"), Cpt104.getBytes());
        Files.write(Paths.get(path + "Cpt105" + ".json"), Cpt105.getBytes());
        Files.write(Paths.get(path + "Cpt106" + ".json"), Cpt106.getBytes());
        Files.write(Paths.get(path + "Cpt107" + ".json"), Cpt107.getBytes());
        Files.write(Paths.get(path + "Cpt108" + ".json"), Cpt108.getBytes());
        Files.write(Paths.get(path + "Cpt109" + ".json"), Cpt109.getBytes());
        Files.write(Paths.get(path + "Cpt110" + ".json"), Cpt110.getBytes());
        Files.write(Paths.get(path + "Cpt111" + ".json"), Cpt111.getBytes());
        Files.write(Paths.get(path + "Cpt11" + ".json"), Cpt11.getBytes());
        Files.write(Paths.get(path + "Cpt11Salt" + ".json"), Cpt11Salt.getBytes());
//        StringBuilder sb = new StringBuilder();
//        sb.append(Cpt101).append("\n").append(Cpt102).append("\n").append(Cpt103).append("\n").append(Cpt104).append("\n").append(Cpt105)
//            .append("\n").append(Cpt106).append("\n").append(Cpt107).append("\n").append(Cpt108).append("\n").append(Cpt109).append("\n").append(Cpt110)
//            .append("\n").append(Cpt111).append("\n").append(Cpt11).append("\n").append(Cpt11Salt);
//        System.out.println(sb.toString());
        System.out.println("end");
    }

    @Test
    public void testGenerateByPojo() {

    }

}
