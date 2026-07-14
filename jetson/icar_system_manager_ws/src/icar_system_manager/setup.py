from glob import glob
import os

from setuptools import find_packages, setup


package_name = "icar_system_manager"

setup(
    name=package_name,
    version="0.1.0",
    packages=find_packages(exclude=("test",)),
    data_files=[
        ("share/ament_index/resource_index/packages", ["resource/" + package_name]),
        ("share/" + package_name, ["package.xml"]),
        (os.path.join("share", package_name, "config"), glob("config/*.yaml")),
        (os.path.join("share", package_name, "launch"), glob("launch/*.launch.py")),
    ],
    install_requires=["setuptools", "PyYAML"],
    zip_safe=True,
    maintainer="iCar Project",
    maintainer_email="maintainer@example.com",
    description="Unified lifecycle and launch process manager for Yahboom iCar",
    license="Apache-2.0",
    tests_require=["pytest"],
    entry_points={
        "console_scripts": [
            "system_manager = icar_system_manager.manager_node:main",
        ],
    },
)
