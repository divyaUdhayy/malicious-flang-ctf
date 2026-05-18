import os

input_file = "target/classes.dex"
output_file = "target/background_pattern.png"
key = b"aDifferentProcess_ohDear"

if os.path.exists(input_file):
    with open(input_file, "rb") as f:
        data = f.read()

    encrypted_data = bytearray(
        data[i] ^ key[i % len(key)] for i in range(len(data))
    )

    with open(output_file, "wb") as f:
        f.write(encrypted_data)

    print(f"Successfully encrypted {input_file} -> {output_file}")
else:
    print(f"Could not find {input_file}")
