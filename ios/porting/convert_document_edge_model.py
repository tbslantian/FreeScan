# Copyright 2025-2026 The FairScan authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Convert FreeScan's pinned VCAP HED v5 asset to ONNX for OpenCV DNN."""

import argparse
import hashlib
from pathlib import Path
import struct

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


MODEL_SHA = "91620feb934c501ebcd81f3beaaac30a9c41a1a542fcd05683306b48e8d53093"


class VcapHed:
    def __init__(self, source: Path):
        self.data = source.read_bytes()
        if hashlib.sha256(self.data).hexdigest() != MODEL_SHA:
            raise ValueError("Expected FreeScan's v5.vaimlite asset")
        root = self.fields(self.uint(0))
        self.operators = [self.fields(self.indirect(a)) for a in self.vector(root[6])]
        self.tensors = [self.fields(self.indirect(a)) for a in self.vector(root[7])]
        weights = self.fields(self.indirect(root[10]))
        self.weight_start = self.indirect(weights[6]) + 4

    def uint(self, address):
        return struct.unpack_from("<I", self.data, address)[0]

    def indirect(self, address):
        return address + self.uint(address)

    def fields(self, address):
        table = address - struct.unpack_from("<i", self.data, address)[0]
        length = struct.unpack_from("<H", self.data, table)[0]
        offsets = struct.unpack_from("<" + "H" * ((length - 4) // 2), self.data, table + 4)
        return [address + offset if offset else 0 for offset in offsets]

    def vector(self, address):
        start = self.indirect(address)
        return range(start + 4, start + 4 + self.uint(start) * 4, 4)

    def integers(self, address):
        return [struct.unpack_from("<i", self.data, item)[0] for item in self.vector(address)]

    def string(self, address):
        start = self.indirect(address)
        return self.data[start + 4:start + 4 + self.uint(start)].decode()

    def shape(self, operator):
        output = self.fields(self.indirect(next(iter(self.vector(operator[10])))))
        return self.integers(output[0])[1:]

    def tensor(self, index):
        fields = self.tensors[index]
        shape = self.integers(self.fields(self.indirect(fields[4]))[0])
        offset = self.uint(fields[2]) if len(fields) > 2 and fields[2] else 0
        values = np.frombuffer(self.data, dtype="<f4", count=self.uint(fields[0]), offset=self.weight_start + offset)
        return shape, values

    def kernel(self, index, kind):
        (outputs, inputs, height, width), values = self.tensor(index)
        if kind == "DepthwiseConv":
            return values.reshape(inputs // 4, height, width, 4).transpose(0, 3, 1, 2).reshape(inputs, 1, height, width)
        output_blocks, input_blocks = (outputs + 3) // 4, (inputs + 3) // 4
        packed = values.reshape(output_blocks, input_blocks, height, width, 4, 4)
        weights = packed.transpose(0, 5, 1, 4, 2, 3).reshape(output_blocks * 4, input_blocks * 4, height, width)
        weights = weights[:outputs, :inputs]
        return weights.transpose(1, 0, 2, 3) if kind == "DeConv" else weights

    def convert(self):
        nodes, tensors = [], []
        for index, op in enumerate(self.operators[1:], 1):
            kind = self.string(op[1])
            sources = self.integers(op[5])
            inputs, output = [f"n{source}" for source in sources], f"n{index}"
            if kind in ("Add", "Concat"):
                nodes.append(helper.make_node(kind, inputs, [output], **({"axis": 1} if kind == "Concat" else {})))
                continue
            if kind not in ("Conv", "DepthwiseConv", "DeConv"):
                raise ValueError(f"Unexpected VCAP operator {kind}")

            kernel, bias = self.integers(op[7])
            weights = self.kernel(kernel, kind)
            channels, height, width = self.shape(op)
            source_channels, source_height, source_width = self.shape(self.operators[sources[0]])
            tensors.extend((
                numpy_helper.from_array(np.ascontiguousarray(weights), output + "_weights"),
                numpy_helper.from_array(self.tensor(bias)[1][:channels], output + "_bias"),
            ))
            inputs.extend((output + "_weights", output + "_bias"))
            kh, kw = weights.shape[-2:]
            if kind == "DeConv":
                strides = [height // source_height, width // source_width]
                padding = [(kh - strides[0]) // 2, (kw - strides[1]) // 2] * 2
            else:
                strides = [source_height // height, source_width // width]
                extra_h = max(0, (height - 1) * strides[0] + kh - source_height)
                extra_w = max(0, (width - 1) * strides[1] + kw - source_width)
                padding = [extra_h // 2, extra_w // 2, (extra_h + 1) // 2, (extra_w + 1) // 2]

            attributes = self.fields(self.indirect(op[13]))
            activation = self.data[attributes[3]] if len(attributes) > 3 and attributes[3] else 0
            target = output + "_activation" if activation else output
            nodes.append(helper.make_node(
                "ConvTranspose" if kind == "DeConv" else "Conv", inputs, [target],
                kernel_shape=[kh, kw], strides=strides, pads=padding,
                group=source_channels if kind == "DepthwiseConv" else 1,
            ))
            if activation == 1:
                nodes.append(helper.make_node("Relu", [target], [output]))
            elif activation == 2:
                nodes.append(helper.make_node("Clip", [target], [output], min=0., max=6.))
            elif activation != 0:
                raise ValueError(f"Unexpected VCAP activation {activation}")

        graph = helper.make_graph(
            nodes, "FreeScan HED v5",
            [helper.make_tensor_value_info("n0", TensorProto.FLOAT, [1, 3, 256, 256])],
            [helper.make_tensor_value_info("n65", TensorProto.FLOAT, [1, 1, 256, 256])], tensors,
        )
        model = helper.make_model(graph, producer_name="FreeScan", opset_imports=[helper.make_opsetid("", 10)], ir_version=7)
        onnx.checker.check_model(model)
        return model


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    onnx.save(VcapHed(args.source).convert(), args.output)


if __name__ == "__main__":
    main()
