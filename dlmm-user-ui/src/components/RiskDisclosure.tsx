import { Alert, Space, Typography } from 'antd'
import { WarningFilled } from '@ant-design/icons'
import Glossary from './Glossary'

const { Text } = Typography

interface Props {
  /** Drives the wording: LP = impermanent loss prominence; SWAP =
   *  slippage prominence; HEDGE = counterparty / settlement. */
  variant: 'lp' | 'swap' | 'hedge'
}

/**
 * Sprint 12 G-14 — risk disclosure banner.
 *
 * Always-on banner на add-liquidity / swap / hedge flows. Compliance
 * required — Anna-driven feedback: «Стоп. Вы только что сказали что я
 * могу потерять. А до этого говорили "это просто как депозит"».
 *
 * Не dismissible by design — compliance + АСВ disclosure должен быть
 * виден КАЖДЫЙ раз, не один раз при первом visit. Тон: нейтрально-
 * информативный, не FUD; warning Alert но не danger.
 *
 * Risk taxonomy:
 *   - lp:    Impermanent loss + АСВ не покрывает + Past performance
 *   - swap:  Slippage + price impact + reverse-swap может быть дороже
 *   - hedge: Counterparty risk + settlement date + курс может качнуться против хеджа
 */
export default function RiskDisclosure({ variant }: Props) {
  const content = {
    lp: (
      <Space direction="vertical" size={4}>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          <Glossary term="il"><Text strong>Impermanent loss</Text></Glossary> возможен, если цена токенов в паре изменится.
          Это значит, что ваша доля в пуле может стоить меньше, чем если бы вы просто держали токены.
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          <Text strong>Это не банковский депозит.</Text> Страхование АСВ <Text strong>не распространяется</Text> на LP-позиции — оно покрывает только балансы SRUB в качестве депозита (до 1.4 млн ₽).
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          Прошлая доходность пула не гарантирует будущую.
        </Text>
      </Space>
    ),
    swap: (
      <Space direction="vertical" size={4}>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          <Glossary term="slippage"><Text strong>Проскальзывание</Text></Glossary> может быть выше ожидаемого, если в пуле мало ликвидности или сделка крупная.
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          Обратный своп (продать и купить обратно) может стоить дороже за счёт комиссий ×2 + price impact.
        </Text>
      </Space>
    ),
    hedge: (
      <Space direction="vertical" size={4}>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          Зафиксированный курс действует только до даты исполнения. После — конвертация по курсу момента.
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          Если рыночный курс пойдёт в вашу пользу, хедж <Text strong>будет стоить</Text> разницы. Это плата за защиту от противоположного движения.
        </Text>
        <Text style={{ fontSize: 'var(--text-sm)' }}>
          Хедж — обязательство, не опцион. Закрыть досрочно можно, но фактическая цена закрытия зависит от рынка на момент закрытия.
        </Text>
      </Space>
    ),
  }[variant]

  return (
    <Alert
      type="warning"
      showIcon
      icon={<WarningFilled />}
      message={<Text strong>Важно знать о рисках</Text>}
      description={content}
      style={{ borderRadius: 'var(--radius-sm)' }}
    />
  )
}
