import { useState } from 'react'
import { Card, Typography, Space, Button, Alert, Modal, Input, Tag, Timeline, Tooltip } from 'antd'
import { LockOutlined, UnlockOutlined, ExclamationCircleOutlined, ClockCircleOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { selfRestriction } from '@/api/services'
import type { SelfRestrictionRow } from '@/api/selfRestriction'
import dayjs from 'dayjs'

const { Title, Text, Paragraph } = Typography

/**
 * Sprint 6 #6.7 — frontend UI for 115-ФЗ самозапрет.
 *
 * <p>Renders the user's current restriction state, full chronological
 * history, and the three lifecycle actions (set / request lift /
 * finalise lift after cooling). Mounted as a section inside ProfilePage.
 *
 * <p>State machine intent (mirrors SelfRestrictionService.isActiveAt
 * on backend):
 *   none      → SET    → RESTRICTED
 *   RESTRICTED → LIFT_REQUESTED (cooling 7d) → after cooling: → finaliseLift → LIFTED
 *   LIFTED    → SET   → RESTRICTED again
 */
export default function SelfRestrictionPanel() {
  const queryClient = useQueryClient()
  const [setModalOpen, setSetModalOpen] = useState(false)
  const [reason, setReason] = useState('')

  const { data: status, isLoading } = useQuery({
    queryKey: ['selfRestriction'],
    queryFn: selfRestriction.getStatus,
  })

  const setMut = useMutation({
    mutationFn: (r: string) => selfRestriction.set(r),
    onSuccess: () => {
      setSetModalOpen(false)
      setReason('')
      queryClient.invalidateQueries({ queryKey: ['selfRestriction'] })
    },
  })

  const liftRequestMut = useMutation({
    mutationFn: () => selfRestriction.requestLift('Запрос пользователя через UI'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['selfRestriction'] }),
  })

  const liftFinaliseMut = useMutation({
    mutationFn: () => selfRestriction.finaliseLift(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['selfRestriction'] }),
  })

  const active = status?.active ?? false
  const history = status?.history ?? []
  const latestLiftRequest = [...history]
    .reverse()
    .find((r) => r.action === 'LIFT_REQUESTED')
  const liftCoolingElapsed = latestLiftRequest
    ? dayjs().isAfter(dayjs(latestLiftRequest.effectiveAt))
    : false

  return (
    <Card
      className="sber-card"
      title={
        <Space>
          {active ? <LockOutlined style={{ color: '#DC2626' }} /> : <UnlockOutlined style={{ color: '#21A038' }} />}
          <Text strong>Самозапрет на новые позиции (115-ФЗ)</Text>
          {active ? (
            <Tag color="red">Активен</Tag>
          ) : (
            <Tag color="green">Не установлен</Tag>
          )}
        </Space>
      }
      loading={isLoading}
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Paragraph type="secondary" style={{ marginBottom: 0 }}>
          Самозапрет блокирует открытие новых позиций (своп, хедж, добавление ликвидности).
          Существующие позиции остаются доступны — вы можете их закрыть или забрать комиссии.
          Снятие самозапрета требует <b>7-дневного периода охлаждения</b> (требование ЦБ РФ).
        </Paragraph>

        {/* Action panel — varies by current state */}
        {!active && (
          <>
            <Alert
              type="info"
              showIcon
              message="У вас нет активного самозапрета"
              description="Вы можете установить самозапрет в любой момент. Это защитная мера — будет полезна если вы планируете перерыв в торговле или хотите ограничить себя от импульсивных операций."
              style={{ borderRadius: 12 }}
            />
            <Button
              type="primary"
              danger
              icon={<LockOutlined />}
              onClick={() => setSetModalOpen(true)}
              size="large"
            >
              Установить самозапрет
            </Button>
          </>
        )}

        {active && !latestLiftRequest && (
          <>
            <Alert
              type="warning"
              showIcon
              message="Самозапрет активен"
              description="Открытие новых позиций (своп, хедж, добавление ликвидности) запрещено. Чтобы снять — запросите снятие, начнётся 7-дневный период охлаждения."
              style={{ borderRadius: 12 }}
            />
            <Button
              icon={<UnlockOutlined />}
              onClick={() => liftRequestMut.mutate()}
              loading={liftRequestMut.isPending}
            >
              Запросить снятие самозапрета
            </Button>
          </>
        )}

        {active && latestLiftRequest && !liftCoolingElapsed && (
          <Alert
            type="warning"
            showIcon
            icon={<ClockCircleOutlined />}
            message="Период охлаждения"
            description={
              <Space direction="vertical">
                <Text>
                  Снятие будет доступно <b>{dayjs(latestLiftRequest.effectiveAt).format('DD.MM.YYYY HH:mm')}</b>
                  {' '}({dayjs(latestLiftRequest.effectiveAt).fromNow()}).
                </Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  До этого момента самозапрет остаётся активным. Это требование ЦБ РФ для защиты пользователей.
                </Text>
              </Space>
            }
            style={{ borderRadius: 12 }}
          />
        )}

        {active && latestLiftRequest && liftCoolingElapsed && (
          <>
            <Alert
              type="success"
              showIcon
              message="Период охлаждения истёк"
              description="Вы можете завершить снятие самозапрета."
              style={{ borderRadius: 12 }}
            />
            <Button
              type="primary"
              icon={<UnlockOutlined />}
              onClick={() => liftFinaliseMut.mutate()}
              loading={liftFinaliseMut.isPending}
            >
              Завершить снятие
            </Button>
          </>
        )}

        {/* History timeline */}
        {history.length > 0 && (
          <>
            <Text strong style={{ fontSize: 13 }}>История</Text>
            <Timeline
              items={history.map((r: SelfRestrictionRow) => ({
                dot: r.action === 'SET' ? <LockOutlined style={{ fontSize: 14, color: '#DC2626' }} /> :
                     r.action === 'LIFTED' ? <UnlockOutlined style={{ fontSize: 14, color: '#21A038' }} /> :
                     <ClockCircleOutlined style={{ fontSize: 14, color: '#F59E0B' }} />,
                children: (
                  <Space direction="vertical" size={2}>
                    <Text strong>
                      {r.action === 'SET' && 'Самозапрет установлен'}
                      {r.action === 'LIFT_REQUESTED' && 'Запрошено снятие (период охлаждения)'}
                      {r.action === 'LIFTED' && 'Самозапрет снят'}
                    </Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {dayjs(r.createdAt).format('DD.MM.YYYY HH:mm')}
                      {r.action === 'LIFT_REQUESTED' && (
                        <Tooltip title={`Снятие доступно с ${dayjs(r.effectiveAt).format('DD.MM.YYYY HH:mm')}`}>
                          {' '}· действует с {dayjs(r.effectiveAt).format('DD.MM.YYYY')}
                        </Tooltip>
                      )}
                    </Text>
                    {r.reason && <Text type="secondary" style={{ fontSize: 12 }}>«{r.reason}»</Text>}
                  </Space>
                ),
              }))}
            />
          </>
        )}
      </Space>

      <Modal
        title={
          <Space>
            <ExclamationCircleOutlined style={{ color: '#DC2626' }} />
            Установить самозапрет
          </Space>
        }
        open={setModalOpen}
        onCancel={() => { setSetModalOpen(false); setReason('') }}
        onOk={() => setMut.mutate(reason)}
        okText="Подтвердить"
        cancelText="Отмена"
        okButtonProps={{ danger: true, loading: setMut.isPending }}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="После установки запрета снять его можно только через 7-дневный период охлаждения"
          />
          <Text>Причина (опционально):</Text>
          <Input.TextArea
            placeholder="Например: «Перерыв в торговле на 2 месяца»"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={500}
          />
        </Space>
      </Modal>
    </Card>
  )
}
