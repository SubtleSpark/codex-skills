#!/bin/bash

# generate_callgraph_json.sh
# 用途：调用 Go 官方 callgraph 工具，输出每行一个 JSON 对象的格式
# 使用：./generate_callgraph_json.sh <项目目录> [输出文件路径]
# 示例：./generate_callgraph_json.sh /path/to/project .tmp/callgraph.json

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 检查参数
if [ $# -eq 0 ]; then
    echo -e "${RED}错误：缺少项目目录参数${NC}"
    echo "使用方法：$0 <项目目录> [输出文件路径]"
    echo "示例：$0 /Users/zyb/work/dev/app/adxserver .tmp/callgraph.json"
    exit 1
fi

# 参数解析
PROJECT_ROOT="$1"
OUTPUT_FILE="${2:-.tmp/callgraph.json}"

# 验证目录存在
if [ ! -d "$PROJECT_ROOT" ]; then
    echo -e "${RED}错误：目录不存在: $PROJECT_ROOT${NC}"
    exit 1
fi

# 切换到项目目录
cd "$PROJECT_ROOT"

# 确保输出目录存在
OUTPUT_DIR=$(dirname "$OUTPUT_FILE")
mkdir -p "$OUTPUT_DIR"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Callgraph JSON 生成器${NC}"
echo -e "${BLUE}========================================${NC}"
echo -e "项目目录: ${GREEN}$PROJECT_ROOT${NC}"
echo -e "输出文件: ${GREEN}$OUTPUT_FILE${NC}"
echo ""

# 检查 callgraph 是否安装
if ! command -v callgraph &> /dev/null; then
    echo -e "${RED}错误：callgraph 未安装${NC}"
    echo "请运行: go install golang.org/x/tools/cmd/callgraph@latest"
    exit 1
fi

# 运行 callgraph
echo -e "${YELLOW}[1/2] 运行 callgraph 生成调用图...${NC}"
echo -e "  使用算法: ${GREEN}vta${NC} (Variable Type Analysis - 精确，无噪音)"
echo ""

# 使用模板格式输出 JSON，每行一条记录
# 创建临时文件存储错误信息
ERROR_FILE=$(mktemp)
trap "rm -f $ERROR_FILE" EXIT

if ! callgraph -algo=vta -format='{"caller":"{{.Caller}}","callee":"{{.Callee}}","line":{{.Line}},"filename":"{{.Filename}}"}' ./... > "$OUTPUT_FILE" 2>"$ERROR_FILE"; then
    echo -e "${RED}callgraph 运行失败${NC}"
    echo ""
    echo -e "${YELLOW}错误信息：${NC}"
    cat "$ERROR_FILE"
    echo ""
    echo -e "${YELLOW}常见解决方法：${NC}"
    echo "  1. 缺少依赖：go mod tidy 或按提示 go get xxx"
    echo "  2. 编译错误：go build ./..."
    echo "  3. 缺少 main 包：需要 main 入口"
    exit 1
fi

# 统计结果
TOTAL_LINES=$(wc -l < "$OUTPUT_FILE" | tr -d ' ')

echo -e "${YELLOW}[2/2] 生成完成${NC}"
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}结果统计${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "  总调用边数: ${GREEN}$TOTAL_LINES${NC}"
echo -e "  输出文件: ${GREEN}$OUTPUT_FILE${NC}"
echo -e "  文件大小: ${GREEN}$(du -h "$OUTPUT_FILE" | cut -f1)${NC}"
echo ""
echo -e "下一步：使用 callgraph-gen 工具生成 Mermaid 流程图"
echo -e "示例：go run .claude/skills/callgraph-gen/main.go \\"
echo -e "        -input=$OUTPUT_FILE \\"
echo -e "        -entry=\"your/package.FuncName\" \\"
echo -e "        -prefix=\"your/\""
